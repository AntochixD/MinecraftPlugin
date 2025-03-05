package me.seunome.claims;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.utils.DatabaseManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.UUID;

public class HouseManager implements Listener, CommandExecutor {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final ClaimManager claimManager;
    private final HashMap<UUID, Location> houseSelection = new HashMap<>(); // Armazena a primeira seleção de casa por jogador

    // Construtor para receber a instância do plugin e gerenciadores
    public HouseManager(JavaPlugin plugin, DatabaseManager databaseManager, ClaimManager claimManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.claimManager = claimManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin); // Registra este listener
    }

    // Método para iniciar a seleção de uma casa com pá de ouro
    public void startHouseSelection(Player player) {
        if (!claimManager.isInClaim(player.getLocation())) {
            player.sendMessage("§cVocê só pode criar casas dentro de um claim!");
            return;
        }

        String playerNation = claimManager.getClaimNation(player.getLocation());
        String playerNationActual = ((MeuPrimeiroPlugin) plugin).getNationManager().getPlayerNation(player);
        if (playerNation == null || !playerNation.equals(playerNationActual)) {
            player.sendMessage("§cVocê só pode criar casas em claims da sua nação!");
            return;
        }

        player.sendMessage("§eUse a pá de ouro para selecionar dois pontos da casa (mínimo 5x5)!");
        houseSelection.put(player.getUniqueId(), null); // Inicia a seleção
    }

    // Método para processar a seleção de uma casa
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getItem() != null && event.getItem().getType() == Material.GOLDEN_SHOVEL) {
            Location loc = event.getClickedBlock().getLocation();
            UUID uuid = player.getUniqueId();

            if (houseSelection.containsKey(uuid)) {
                Location firstPoint = houseSelection.get(uuid);
                if (firstPoint == null) {
                    if (!claimManager.isInClaim(loc)) {
                        player.sendMessage("§cVocê só pode selecionar pontos dentro de um claim!");
                        return;
                    }
                    houseSelection.put(uuid, loc);
                    player.sendMessage("§aPrimeiro ponto marcado em X: " + loc.getBlockX() + ", Z: " + loc.getBlockZ());
                } else {
                    if (!claimManager.isInClaim(loc)) {
                        player.sendMessage("§cVocê só pode selecionar pontos dentro de um claim!");
                        return;
                    }
                    houseSelection.remove(uuid);
                    createHouse(player, firstPoint, loc);
                }
                event.setCancelled(true); // Cancela a interação com o bloco
            }
        }
    }

    // Método para criar uma casa
    private void createHouse(Player player, Location loc1, Location loc2) {
        if (!claimManager.isInClaim(loc1) || !claimManager.isInClaim(loc2)) {
            player.sendMessage("§cAmbos os pontos devem estar dentro de um claim!");
            return;
        }

        String playerNation = ((MeuPrimeiroPlugin) plugin).getNationManager().getPlayerNation(player);
        String claimNation = claimManager.getClaimNation(loc1);
        if (playerNation == null || !playerNation.equals(claimNation)) {
            player.sendMessage("§cVocê só pode criar casas em claims da sua nação!");
            return;
        }

        // Calcula as coordenadas da casa (mínimo 5x5, configurável)
        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());

        if ((maxX - minX + 1) < 5 || (maxZ - minZ + 1) < 5) {
            player.sendMessage("§cA casa deve ter pelo menos 5x5 blocos!");
            return;
        }

        String world = loc1.getWorld().getName();

        // Verifica se o terreno está disponível dentro do claim
        if (!claimManager.isTerrainAvailable(minX, maxX, minZ, maxZ, world, playerNation)) {
            player.sendMessage("§cParte deste terreno já está ocupada por outra casa ou claim!");
            return;
        }

        // Calcula o custo (10 dólares por bloco, configurável)
        int blocks = (maxX - minX + 1) * (maxZ - minZ + 1) * (500 - (-64) + 1); // Altura de -64 a 500
        double cost = blocks * plugin.getConfig().getDouble("house-block-cost", 10.0);
        if (((MeuPrimeiroPlugin) plugin).getEconomyManager().getBalance(player) < cost) {
            player.sendMessage("§cVocê não tem dólares suficientes! Custo: " + cost + " dólares.");
            return;
        }

        // Remove o custo do jogador
        ((MeuPrimeiroPlugin) plugin).getEconomyManager().removeBalance(player, cost);

        // Salva a casa no banco de dados
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO houses (owner, nation, world, min_x, max_x, min_z, max_z) VALUES (?, ?, ?, ?, ?, ?, ?)");
            stmt.setString(1, player.getUniqueId().toString());
            stmt.setString(2, playerNation);
            stmt.setString(3, world);
            stmt.setInt(4, minX);
            stmt.setInt(5, maxX);
            stmt.setInt(6, minZ);
            stmt.setInt(7, maxZ);
            stmt.executeUpdate();

            player.sendMessage("§aCasa criada com sucesso por " + cost + " dólares na nação " + playerNation + "!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao criar casa: " + e.getMessage());
            player.sendMessage("§cErro ao criar casa. Contate um administrador!");
        }
    }

    // Método para proteger casas (eventos)
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();
        if (isInHouse(loc) && !isHouseOwner(player, loc)) {
            event.setCancelled(true);
            player.sendMessage("§cVocê não tem permissão para quebrar blocos aqui!");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();
        if (isInHouse(loc) && !isHouseOwner(player, loc)) {
            event.setCancelled(true);
            player.sendMessage("§cVocê não tem permissão para colocar blocos aqui!");
        }
    }

    // Método para verificar se uma localização está em uma casa
    private boolean isInHouse(Location loc) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM houses WHERE world = ? AND min_x <= ? AND max_x >= ? AND min_z <= ? AND max_z >= ?");
            stmt.setString(1, loc.getWorld().getName());
            stmt.setInt(2, loc.getBlockX());
            stmt.setInt(3, loc.getBlockX());
            stmt.setInt(4, loc.getBlockZ());
            stmt.setInt(5, loc.getBlockZ());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao verificar casa: " + e.getMessage());
        }
        return false;
    }

    // Método para verificar se o jogador é dono da casa
    private boolean isHouseOwner(Player player, Location loc) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT owner FROM houses WHERE world = ? AND min_x <= ? AND max_x >= ? AND min_z <= ? AND max_z >= ?");
            stmt.setString(1, loc.getWorld().getName());
            stmt.setInt(2, loc.getBlockX());
            stmt.setInt(3, loc.getBlockX());
            stmt.setInt(4, loc.getBlockZ());
            stmt.setInt(5, loc.getBlockZ());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                UUID ownerUUID = UUID.fromString(rs.getString("owner"));
                return ownerUUID.equals(player.getUniqueId());
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao verificar dono da casa: " + e.getMessage());
        }
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando só pode ser usado por jogadores!");
            return true;
        }

        Player player = (Player) sender;
        if (command.getName().equalsIgnoreCase("house")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("create")) {
                startHouseSelection(player);
                return true;
            } else {
                player.sendMessage("§cUso: /house create");
                return true;
            }
        }
        return false;
    }
}