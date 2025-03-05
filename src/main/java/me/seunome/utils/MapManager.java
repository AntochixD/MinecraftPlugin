package me.seunome.utils;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.claims.ClaimManager;
import me.seunome.geopolitics.AllianceManager;
import me.seunome.geopolitics.NationManager;
import me.seunome.geopolitics.WarManager;

public class MapManager implements CommandExecutor {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final NationManager nationManager;
    private final ClaimManager claimManager;
    private final WarManager warManager;
    private final AllianceManager allianceManager; // Adiciona AllianceManager
    private final Map<Integer, MapView> dynamicMaps = new HashMap<>(); // Armazena mapas dinâmicos por ID

    // Construtor para receber a instância do plugin e gerenciadores
    public MapManager(JavaPlugin plugin, DatabaseManager databaseManager, NationManager nationManager, ClaimManager claimManager, WarManager warManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.nationManager = nationManager;
        this.claimManager = claimManager;
        this.warManager = warManager;
        this.allianceManager = ((MeuPrimeiroPlugin) plugin).getAllianceManager(); // Inicializa AllianceManager
        startMapUpdateTask();
    }

    // Método para exibir um mapa dinâmico com /map
    public void showDynamicMap(Player player) {
        MapView mapView = Bukkit.createMap(player.getWorld());
        mapView.setScale(MapView.Scale.FARTHEST); // Zoom 3 (maior escala)
        mapView.getRenderers().clear(); // Remove renderers padrão
        mapView.addRenderer(new MapRenderer() {
            @Override
            public void render(MapView map, MapCanvas canvas, Player viewer) {
                drawMapContent(map, canvas, viewer);
            }
        });

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(mapView);
        mapItem.setItemMeta(meta);

        player.getInventory().addItem(mapItem);
        player.sendMessage("§aMapa dinâmico criado! Use-o para ver territórios, nações, alianças e áreas de guerra.");
        dynamicMaps.put(mapView.getId(), mapView);
    }

    // Método para desenhar o conteúdo do mapa
    private void drawMapContent(MapView map, MapCanvas canvas, Player viewer) {
        int centerX = 64; // Centro do mapa (128x128 pixels)
        int centerZ = 64;
        Location playerLoc = viewer.getLocation();

        // Desenha o mapa com territórios, nações, alianças e áreas de guerra
        Map<Location, String> claims = claimManager.getAllClaimLocations();
        for (Map.Entry<Location, String> entry : claims.entrySet()) {
            Location loc = entry.getKey();
            String nation = entry.getValue();
            if (nation == null || !loc.getWorld().getName().equals(playerLoc.getWorld().getName())) continue;

            // Converte coordenadas do mundo para pixels do mapa (escala 1:1 por bloco no zoom 3)
            int mapMinX = centerX + (loc.getBlockX() - playerLoc.getBlockX()) / 4; // Dividido por 4 para zoom 3
            int mapMaxX = mapMinX + 1; // Simplificado para um bloco por pixel (ajuste para claims maiores)
            int mapMinZ = centerZ + (loc.getBlockZ() - playerLoc.getBlockZ()) / 4;
            int mapMaxZ = mapMinZ + 1;

            // Verifica se o claim está em guerra
            boolean isInWar = isClaimInWar(loc, nation);
            // Verifica se o claim pertence a uma aliança
            String alliance = getAllianceForNation(nation);

            Color color = getColorForNationOrAllianceOrWar(nation, alliance, isInWar);

            // Preenche o retângulo com a cor
            for (int x = mapMinX; x <= mapMaxX && x < 128; x++) {
                for (int z = mapMinZ; z <= mapMaxZ && z < 128; z++) {
                    if (x >= 0 && z >= 0) {
                        canvas.setPixelColor(x, z, color);
                    }
                }
            }

            // Desenha o rótulo (aliança, guerra, ou nação) usando MapFont
            String label = "N/A";
            if (isInWar) {
                label = "Guerra";
            } else if (alliance != null) {
                label = alliance.length() > 10 ? alliance.substring(0, 10) + "..." : alliance;
            } else {
                label = nation.length() > 10 ? nation.substring(0, 10) + "..." : nation;
            }
            canvas.drawText(mapMinX, mapMinZ - 2, org.bukkit.map.MinecraftFont.Font, label); // Corrigido para MinecraftFont.Font
        }
    }

    // Método para verificar se um claim está em guerra
    private boolean isClaimInWar(Location loc, String nation) {
        Map<String, Map<String, Long>> wars = warManager.getActiveWars(); // Chama o método corrigido em WarManager
        for (String attacker : wars.keySet()) {
            for (String defender : wars.get(attacker).keySet()) {
                if (nation.equals(defender) || nation.equals(attacker)) {
                    if (claimManager.isInClaim(loc)) {
                        return true; // Placeholder: refine para verificar áreas específicas de guerra
                    }
                }
            }
        }
        return false;
    }

    // Método para obter a aliança de uma nação
    private String getAllianceForNation(String nation) {
        for (String alliance : allianceManager.getAlliances().keySet()) {
            if (allianceManager.isNationInAlliance(nation, alliance)) {
                return alliance;
            }
        }
        return null;
    }

    // Método para obter a cor de uma nação, aliança ou área de guerra
    private Color getColorForNationOrAllianceOrWar(String nation, String alliance, boolean isInWar) {
        if (isInWar) {
            return Color.ORANGE; // Cor laranja para áreas de guerra
        } else if (alliance != null) {
            String colorHex = plugin.getConfig().getString("alliance-colors." + alliance);
            if (colorHex != null && colorHex.startsWith("#")) {
                try {
                    return Color.decode(colorHex);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("§eCor inválida para aliança " + alliance + ": " + colorHex);
                }
            }
            return Color.YELLOW; // Padrão para alianças se não configurado
        } else {
            String colorHex = plugin.getConfig().getString("nation-colors." + nation);
            if (colorHex != null && colorHex.startsWith("#")) {
                try {
                    return Color.decode(colorHex);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("§eCor inválida para nação " + nation + ": " + colorHex);
                }
            }
            return Color.WHITE; // Padrão para nações se não configurado
        }
    }

    // Método para atualizar mapas craftados automaticamente
    private void startMapUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (MapView map : dynamicMaps.values()) {
                    map.getRenderers().clear();
                    map.addRenderer(new MapRenderer() {
                        @Override
                        public void render(MapView map, MapCanvas canvas, Player viewer) {
                            drawMapContent(map, canvas, viewer);
                        }
                    });
                }
            }
        }.runTaskTimer(plugin, 0L, 20 * 60 * plugin.getConfig().getInt("map-update-interval", 5)); // Intervalo configurável em minutos
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando só pode ser usado por jogadores!");
            return true;
        }

        Player player = (Player) sender;
        if (command.getName().equalsIgnoreCase("map")) {
            showDynamicMap(player);
            return true;
        }
        return false;
    }
}