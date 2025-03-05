package me.seunome.claims;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
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

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.geopolitics.NationManager;
import me.seunome.utils.DatabaseManager;

public class ClaimManager implements Listener, CommandExecutor {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final NationManager nationManager;
    private final HashMap<UUID, Location> claimSelection = new HashMap<>(); // Armazena a primeira seleção de claim por jogador
    private final HashMap<UUID, Location> deleteSelection = new HashMap<>(); // Armazena a primeira seleção para deleção
    private final HashMap<UUID, Boolean> deletionConfirm = new HashMap<>(); // Armazena confirmação de deleção

    // Construtor para receber a instância do plugin e o gerenciador de banco de dados
    public ClaimManager(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.nationManager = ((MeuPrimeiroPlugin) plugin).getNationManager();
        plugin.getServer().getPluginManager().registerEvents(this, plugin); // Registra este listener
    }

    // Método para iniciar a seleção de um claim com pá de madeira
    public void startClaimSelection(Player player) {
        if (!nationManager.isNationLeader(player)) {
            player.sendMessage("§cApenas o líder da nação pode reivindicar terrenos!");
            return;
        }
        player.sendMessage("§eUse a pá de madeira para selecionar dois pontos do terreno!");
        claimSelection.put(player.getUniqueId(), null); // Inicia a seleção
    }

    // Método para iniciar a deleção de um claim com pá de madeira
    public void startDeleteSelection(Player player) {
        if (!nationManager.isNationLeader(player)) {
            player.sendMessage("§cApenas o líder da nação pode deletar terrenos!");
            return;
        }
        player.sendMessage("§eUse a pá de madeira para selecionar um ponto do terreno a deletar. Confirme com /claim delete confirm!");
        deleteSelection.put(player.getUniqueId(), null); // Inicia a seleção para deleção
        deletionConfirm.put(player.getUniqueId(), false); // Reseta a confirmação
    }

    // Método para confirmar a deleção
    public void confirmDelete(Player player) {
        UUID uuid = player.getUniqueId();
        if (deleteSelection.containsKey(uuid) && deleteSelection.get(uuid) != null) {
            deletionConfirm.put(uuid, true);
            player.sendMessage("§eConfirmação recebida! Use a pá de madeira para marcar o segundo ponto e deletar o claim.");
        } else {
            player.sendMessage("§cVocê não iniciou a seleção de deleção ou o primeiro ponto não foi marcado!");
        }
    }

    // Método para processar a seleção de um claim
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getItem() != null && event.getItem().getType() == Material.WOODEN_SHOVEL) {
            Location loc = event.getClickedBlock().getLocation();
            UUID uuid = player.getUniqueId();

            // Seleção para criar um claim
            if (claimSelection.containsKey(uuid)) {
                Location firstPoint = claimSelection.get(uuid);
                if (firstPoint == null) {
                    claimSelection.put(uuid, loc);
                    player.sendMessage("§aPrimeiro ponto marcado em X: " + loc.getBlockX() + ", Z: " + loc.getBlockZ());
                } else {
                    claimSelection.remove(uuid);
                    createClaim(player, firstPoint, loc);
                }
                event.setCancelled(true); // Cancela a interação com o bloco
            }

            // Seleção para deletar um claim
            if (deleteSelection.containsKey(uuid)) {
                Location firstPoint = deleteSelection.get(uuid);
                if (firstPoint == null) {
                    deleteSelection.put(uuid, loc);
                    player.sendMessage("§aPrimeiro ponto marcado para deleção em X: " + loc.getBlockX() + ", Z: " + loc.getBlockZ());
                } else if (deletionConfirm.get(uuid)) {
                    deleteSelection.remove(uuid);
                    deletionConfirm.remove(uuid);
                    deleteClaim(player, firstPoint, loc);
                }
                event.setCancelled(true); // Cancela a interação com o bloco
            }
        }
    }

    // Método para criar um claim (reivindicar terreno)
    private void createClaim(Player player, Location loc1, Location loc2) {
        if (!nationManager.isNationLeader(player)) {
            player.sendMessage("§cApenas o líder da nação pode reivindicar terrenos!");
            return;
        }

        String nation = nationManager.getPlayerNation(player);
        if (nation == null) {
            player.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        // Calcula as coordenadas do claim (mínimo e máximo X, Z)
        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());
        String world = loc1.getWorld().getName();

        // Verifica se o terreno está disponível
        if (!isTerrainAvailable(minX, maxX, minZ, maxZ, world, nation)) {
            player.sendMessage("§cParte deste terreno pertence a outra nação ou está indisponível!");
            return;
        }

        // Calcula o custo (configurável por bloco, toda altura Y)
        int blocks = (maxX - minX + 1) * (maxZ - minZ + 1) * (500 - (-64) + 1); // Altura de -64 a 500
        double cost = blocks * getClaimBlockCost();
        if (((MeuPrimeiroPlugin) plugin).getEconomyManager().getBalance(player) < cost) {
            player.sendMessage("§cVocê não tem dólares suficientes! Custo: " + cost + " dólares.");
            return;
        }

        // Remove o custo do jogador
        ((MeuPrimeiroPlugin) plugin).getEconomyManager().removeBalance(player, cost);

        // Salva o claim no banco de dados
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO claims (nation, world, min_x, max_x, min_z, max_z) VALUES (?, ?, ?, ?, ?, ?)");
            stmt.setString(1, nation);
            stmt.setString(2, world);
            stmt.setInt(3, minX);
            stmt.setInt(4, maxX);
            stmt.setInt(5, minZ);
            stmt.setInt(6, maxZ);
            stmt.executeUpdate();
            player.sendMessage("§aTerreno reivindicado com sucesso por " + cost + " dólares!");

            // Mostra partículas no claim
            showClaimParticles(loc1, loc2, nation);
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao salvar claim: " + e.getMessage());
            player.sendMessage("§cErro ao reivindicar terreno. Contate um administrador!");
        }
    }

    // Método para deletar um claim
    private void deleteClaim(Player player, Location loc1, Location loc2) {
        if (!nationManager.isNationLeader(player)) {
            player.sendMessage("§cApenas o líder da nação pode deletar terrenos!");
            return;
        }

        String nation = nationManager.getPlayerNation(player);
        if (nation == null) {
            player.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());
        String world = loc1.getWorld().getName();

        // Verifica se o terreno pertence à nação do jogador
        if (!isClaimOwnedByNation(minX, maxX, minZ, maxZ, world, nation)) {
            player.sendMessage("§cEste terreno não pertence à sua nação!");
            return;
        }

        // Deleta o claim do banco de dados
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM claims WHERE nation = ? AND world = ? AND min_x = ? AND max_x = ? AND min_z = ? AND max_z = ?");
            stmt.setString(1, nation);
            stmt.setString(2, world);
            stmt.setInt(3, minX);
            stmt.setInt(4, maxX);
            stmt.setInt(5, minZ);
            stmt.setInt(6, maxZ);
            int rowsAffected = stmt.executeUpdate();

            if (rowsAffected > 0) {
                player.sendMessage("§aTerreno deletado com sucesso!");
                showClaimParticles(loc1, loc2, null); // Mostra partículas para indicar remoção
            } else {
                player.sendMessage("§cNenhum claim encontrado para deletar!");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao deletar claim: " + e.getMessage());
            player.sendMessage("§cErro ao deletar terreno. Contate um administrador!");
        }
    }

    // Método para verificar se o claim pertence à nação
    private boolean isClaimOwnedByNation(int minX, int maxX, int minZ, int maxZ, String world, String nation) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM claims WHERE nation = ? AND world = ? AND min_x = ? AND max_x = ? AND min_z = ? AND max_z = ?");
            stmt.setString(1, nation);
            stmt.setString(2, world);
            stmt.setInt(3, minX);
            stmt.setInt(4, maxX);
            stmt.setInt(5, minZ);
            stmt.setInt(6, maxZ);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao verificar propriedade do claim: " + e.getMessage());
        }
        return false;
    }

    // Método para verificar se uma localização está em um claim
    public boolean isInClaim(Location loc) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM claims WHERE world = ? AND min_x <= ? AND max_x >= ? AND min_z <= ? AND max_z >= ?");
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
            plugin.getLogger().severe("§cErro ao verificar claim: " + e.getMessage());
        }
        return false;
    }

    // Método para obter a nação que possui um claim
    public String getClaimNation(Location loc) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT nation FROM claims WHERE world = ? AND min_x <= ? AND max_x >= ? AND min_z <= ? AND max_z >= ?");
            stmt.setString(1, loc.getWorld().getName());
            stmt.setInt(2, loc.getBlockX());
            stmt.setInt(3, loc.getBlockX());
            stmt.setInt(4, loc.getBlockZ());
            stmt.setInt(5, loc.getBlockZ());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("nation");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao obter nação do claim: " + e.getMessage());
        }
        return null;
    }

    // Método para verificar se o terreno está disponível (não pertence a outra nação)
    public boolean isTerrainAvailable(int minX, int maxX, int minZ, int maxZ, String world, String nation) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT nation FROM claims WHERE world = ? AND ((min_x <= ? AND max_x >= ?) OR (min_x <= ? AND max_x >= ?)) " +
                    "AND ((min_z <= ? AND max_z >= ?) OR (min_z <= ? AND max_z >= ?))");
            stmt.setString(1, world);
            stmt.setInt(2, maxX); stmt.setInt(3, minX); // Verifica sobreposição em X
            stmt.setInt(4, maxX); stmt.setInt(5, minX);
            stmt.setInt(6, maxZ); stmt.setInt(7, minZ); // Verifica sobreposição em Z
            stmt.setInt(8, maxZ); stmt.setInt(9, minZ);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String claimNation = rs.getString("nation");
                if (claimNation != null && !claimNation.equals(nation)) {
                    return false; // Terreno pertence a outra nação
                }
            }
            return true; // Terreno disponível ou pertence à mesma nação
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao verificar terreno disponível: " + e.getMessage());
            return false;
        }
    }

    // Método para obter o custo por bloco do config.yml
    private double getClaimBlockCost() {
        return plugin.getConfig().getDouble("claim-block-cost", 10.0);
    }

    // Método para obter o tamanho mínimo de claims (opcional, reutiliza city-min-size)
    private int getClaimMinSize() {
        return plugin.getConfig().getInt("city-min-size", 20);
    }

    // Método para obter todas as localizações de claims (retorna localizações representativas)
    public Map<Location, String> getAllClaimLocations() {
        Map<Location, String> claimLocations = new HashMap<>();
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT nation, world, min_x, min_z FROM claims");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String nation = rs.getString("nation");
                String world = rs.getString("world");
                int minX = rs.getInt("min_x");
                int minZ = rs.getInt("min_z");
                Location loc = new Location(Bukkit.getWorld(world), minX, 0, minZ); // Usa o canto inferior como referência
                claimLocations.put(loc, nation);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao listar claims: " + e.getMessage());
        }
        return claimLocations;
    }

    // Método para mostrar partículas nos limites do claim
    private void showClaimParticles(Location loc1, Location loc2, String nation) {
        World world = loc1.getWorld();
        if (world == null) return;

        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());

        Particle particle = Particle.valueOf(plugin.getConfig().getString("particle-color-claim", "BARRIER"));
        for (int x = minX; x <= maxX; x += 5) {
            for (int z = minZ; z <= maxZ; z += 5) {
                world.spawnParticle(particle, x, world.getHighestBlockYAt(x, z), z, 10, 0.5, 0.5, 0.5, 0.1);
            }
        }
    }

    // Eventos de proteção
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();
        if (isInClaim(loc) && !hasPermission(player, loc, "build")) {
            event.setCancelled(true);
            player.sendMessage("§cVocê não tem permissão para quebrar blocos aqui!");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();
        if (isInClaim(loc) && !hasPermission(player, loc, "build")) {
            event.setCancelled(true);
            player.sendMessage("§cVocê não tem permissão para colocar blocos aqui!");
        }
    }

    // Método para verificar permissões específicas no claim
    private boolean hasPermission(Player player, Location loc, String permission) {
        String playerNation = nationManager.getPlayerNation(player);
        String claimNation = getClaimNation(loc);
        if (claimNation == null) return false;

        if (playerNation == null) return false;

        // Líder da nação tem todas as permissões
        if (nationManager.isNationLeader(player)) return true;

        // Membros da nação têm permissões básicas
        if (playerNation.equals(claimNation)) {
            if ("build".equals(permission)) return true;
            if ("interact".equals(permission)) return true;
            if ("chest".equals(permission) || "door".equals(permission)) return false; // Só líderes podem abrir baús/portas por padrão
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
        if (command.getName().equalsIgnoreCase("claim")) {
            if (args.length == 0) {
                player.sendMessage("§cUso: /claim [create|delete|delete confirm]");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "create":
                    startClaimSelection(player);
                    return true;
                case "delete":
                    startDeleteSelection(player);
                    return true;
                case "delete confirm":
                    confirmDelete(player);
                    return true;
                default:
                    player.sendMessage("§cComando inválido! Use /claim [create|delete|delete confirm]");
                    return true;
            }
        }
        return false;
    }
}