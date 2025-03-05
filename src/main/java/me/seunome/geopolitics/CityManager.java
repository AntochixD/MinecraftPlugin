package me.seunome.geopolitics;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.claims.ClaimManager;
import me.seunome.economy.EconomyManager;
import me.seunome.utils.DatabaseManager;
import me.seunome.utils.PermissionManager;

public class CityManager implements Listener {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final NationManager nationManager;
    private final ClaimManager claimManager;
    private final EconomyManager economyManager;
    private final PermissionManager permissionManager; // Novo campo para PermissionManager
    private final HashMap<UUID, Location> citySelection = new HashMap<>(); // Armazena a primeira seleção de cidade por jogador

    // Construtor para receber a instância do plugin e gerenciadores
    public CityManager(JavaPlugin plugin, DatabaseManager databaseManager, NationManager nationManager, ClaimManager claimManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.nationManager = nationManager;
        this.claimManager = claimManager;
        this.economyManager = ((MeuPrimeiroPlugin) plugin).getEconomyManager();
        this.permissionManager = ((MeuPrimeiroPlugin) plugin).getPermissionManager(); // Inicializa PermissionManager
        plugin.getServer().getPluginManager().registerEvents(this, plugin); // Registra este listener
    }

    // Método para iniciar a seleção de uma cidade com pá de madeira
    public void startCitySelection(Player player) {
        if (!nationManager.isNationLeader(player)) {
            player.sendMessage("§cApenas o líder da nação pode criar cidades!");
            return;
        }

        player.sendMessage("§eUse a pá de madeira para selecionar dois pontos da cidade (mínimo " + plugin.getConfig().getInt("city-min-size", 20) + "x" + plugin.getConfig().getInt("city-min-size", 20) + ")!");
        citySelection.put(player.getUniqueId(), null); // Inicia a seleção
    }

    // Método para processar a seleção de uma cidade
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getItem() != null && event.getItem().getType() == Material.WOODEN_SHOVEL) {
            Location loc = event.getClickedBlock().getLocation();
            UUID uuid = player.getUniqueId();

            if (citySelection.containsKey(uuid)) {
                Location firstPoint = citySelection.get(uuid);
                if (firstPoint == null) {
                    citySelection.put(uuid, loc);
                    player.sendMessage("§aPrimeiro ponto marcado em X: " + loc.getBlockX() + ", Z: " + loc.getBlockZ());
                } else {
                    citySelection.remove(uuid);
                    createCity(player, firstPoint, loc);
                }
                event.setCancelled(true); // Cancela a interação com o bloco
            }
        }
    }

    // Método para criar uma cidade
    private void createCity(Player player, Location loc1, Location loc2) {
        if (!nationManager.isNationLeader(player)) {
            player.sendMessage("§cApenas o líder da nação pode criar cidades!");
            return;
        }

        String nation = nationManager.getPlayerNation(player);
        if (nation == null) {
            player.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        // Calcula as coordenadas da cidade (mínimo configurável)
        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());

        int minSize = plugin.getConfig().getInt("city-min-size", 20);
        if ((maxX - minX + 1) < minSize || (maxZ - minZ + 1) < minSize) {
            player.sendMessage("§cA cidade deve ter pelo menos " + minSize + "x" + minSize + " blocos!");
            return;
        }

        String world = loc1.getWorld().getName();

        // Verifica se o terreno está disponível e dentro de um claim da nação
        if (!claimManager.isTerrainAvailable(minX, maxX, minZ, maxZ, world, nation)) {
            player.sendMessage("§cParte deste terreno pertence a outra nação ou está indisponível!");
            return;
        }

        // Calcula o custo (configurável por bloco)
        int blocks = (maxX - minX + 1) * (maxZ - minZ + 1) * (500 - (-64) + 1); // Altura de -64 a 500
        double cost = blocks * plugin.getConfig().getDouble("city-block-cost", 10.0);
        if (economyManager.getBalance(player) < cost) {
            player.sendMessage("§cVocê não tem dólares suficientes! Custo: " + cost + " dólares.");
            return;
        }

        // Remove o custo do jogador
        economyManager.removeBalance(player, cost);

        // Salva a cidade no banco de dados
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO cities (nation, name, world, min_x, max_x, min_z, max_z) VALUES (?, ?, ?, ?, ?, ?, ?)");
            stmt.setString(1, nation);
            stmt.setString(2, "Cidade" + (getCityCount(nation) + 1)); // Nome padrão (ex.: Cidade1, Cidade2)
            stmt.setString(3, world);
            stmt.setInt(4, minX);
            stmt.setInt(5, maxX);
            stmt.setInt(6, minZ);
            stmt.setInt(7, maxZ);
            stmt.executeUpdate();

            player.sendMessage("§aCidade criada com sucesso por " + cost + " dólares na nação " + nation + "!");

            // Mostra partículas no claim
            showClaimParticles(loc1, loc2, nation);
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao criar cidade: " + e.getMessage());
            player.sendMessage("§cErro ao criar cidade. Contate um administrador!");
        }
    }

    // Método para contar o número de cidades de uma nação
    private int getCityCount(String nation) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM cities WHERE nation = ?");
            stmt.setString(1, nation);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao contar cidades: " + e.getMessage());
        }
        return 0;
    }

    // Método para mostrar partículas nos limites da cidade
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

    // Método para obter o EconomyManager do plugin
    private EconomyManager getEconomyManager() {
        return ((MeuPrimeiroPlugin) plugin).getEconomyManager();
    }

    // Método para abrir o menu de cidades
    public void openCityMenu(Player player) {
        String nation = nationManager.getPlayerNation(player);
        if (nation == null) {
            player.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!nationManager.isNationLeader(player) && !permissionManager.hasPermission(player, "geocraft.nation.member")) {
            player.sendMessage("§cVocê não tem permissão para acessar este comando!");
            return;
        }

        Inventory cityMenu = Bukkit.createInventory(null, 27, "Cidades da Nação " + nation);
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT name, world, min_x, max_x, min_z, max_z FROM cities WHERE nation = ?");
            stmt.setString(1, nation);
            ResultSet rs = stmt.executeQuery();

            int slot = 0;
            while (rs.next() && slot < 27) { // Limita a 27 slots (3 linhas)
                String cityName = rs.getString("name");
                String worldName = rs.getString("world");
                int minX = rs.getInt("min_x");
                int maxX = rs.getInt("max_x");
                int minZ = rs.getInt("min_z");
                int maxZ = rs.getInt("max_z");

                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;

                // Calcula o centro da cidade (média das coordenadas X/Z, altura do terreno mais alto)
                int centerX = (minX + maxX) / 2;
                int centerZ = (minZ + maxZ) / 2;
                int centerY = world.getHighestBlockYAt(centerX, centerZ);

                Location cityLocation = new Location(world, centerX, centerY, centerZ);

                // Cria item para a cidade (usando sinalização como ícone)
                ItemStack cityItem = new ItemStack(Material.OAK_SIGN);
                ItemMeta meta = cityItem.getItemMeta();
                meta.setDisplayName("§e" + cityName);
                meta.setLore(Arrays.asList("§7Clique para teleportar", "§7X: " + centerX + ", Z: " + centerZ));
                cityItem.setItemMeta(meta);

                cityMenu.setItem(slot, cityItem);
                slot++;
            }

            player.openInventory(cityMenu);
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao carregar cidades: " + e.getMessage());
            player.sendMessage("§cErro ao abrir o menu de cidades. Contate um administrador!");
        }
    }

    // Método para lidar com cliques no inventário
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().startsWith("Cidades da Nação ")) return;

        Player player = (Player) event.getWhoClicked();
        event.setCancelled(true); // Cancela o clique padrão

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || !clickedItem.hasItemMeta()) return;

        String cityName = clickedItem.getItemMeta().getDisplayName().replace("§e", "").trim();
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT world, min_x, max_x, min_z, max_z FROM cities WHERE nation = ? AND name = ?");
            stmt.setString(1, nationManager.getPlayerNation(player));
            stmt.setString(2, cityName);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String worldName = rs.getString("world");
                int minX = rs.getInt("min_x");
                int maxX = rs.getInt("max_x");
                int minZ = rs.getInt("min_z");
                int maxZ = rs.getInt("max_z");

                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    player.sendMessage("§cMundo da cidade não encontrado!");
                    return;
                }

                // Calcula o centro da cidade
                int centerX = (minX + maxX) / 2;
                int centerZ = (minZ + maxZ) / 2;
                int centerY = world.getHighestBlockYAt(centerX, centerZ);

                Location cityLocation = new Location(world, centerX, centerY, centerZ);
                player.teleport(cityLocation);
                player.sendMessage("§aTeleportado para " + cityName + "!");
                player.closeInventory();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao teleportar para cidade: " + e.getMessage());
            player.sendMessage("§cErro ao teleportar. Contate um administrador!");
        }
    }
}