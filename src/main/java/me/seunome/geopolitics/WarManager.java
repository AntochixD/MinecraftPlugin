package me.seunome.geopolitics;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.claims.ClaimManager;
import me.seunome.economy.EconomyManager;
import me.seunome.utils.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WarManager implements Listener {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final NationManager nationManager;
    private final ClaimManager claimManager;
    private final EconomyManager economyManager;
    private final AllianceManager allianceManager; // AllianceManager existente
    private final Map<String, Map<String, Long>> activeWars = new HashMap<>(); // Atacante → {Defensor → Timestamp de início}
    private final Map<UUID, Location> conquestSelection = new HashMap<>(); // Jogador → Primeira localização de conquista
    private final HashMap<String, BossBar> warBossBars = new HashMap<>(); // Armazena bossbars por guerra
    private final Map<String, Map<String, Long>> surrenderProposals = new HashMap<>(); // Atacante → {Defensor → Timestamp de expiração}

    // Construtor para receber a instância do plugin e gerenciadores
    public WarManager(JavaPlugin plugin, DatabaseManager databaseManager, NationManager nationManager, ClaimManager claimManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.nationManager = nationManager;
        this.claimManager = claimManager;
        this.economyManager = ((MeuPrimeiroPlugin) plugin).getEconomyManager();
        this.allianceManager = ((MeuPrimeiroPlugin) plugin).getAllianceManager(); // Mantém AllianceManager
        plugin.getServer().getPluginManager().registerEvents(this, plugin); // Registra este listener

        // Inicia a manutenção das guerras (custos por hora)
        startWarMaintenance();
    }

    // Método para declarar uma guerra
    public void declareWar(Player leader, String targetNation) {
        String attackerNation = nationManager.getPlayerNation(leader);
        if (attackerNation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas o líder da nação pode declarar guerras!");
            return;
        }

        if (!nationExists(targetNation)) {
            leader.sendMessage("§cEsta nação não existe!");
            return;
        }

        if (isWarActive(attackerNation, targetNation)) {
            leader.sendMessage("§cJá existe uma guerra ativa contra esta nação!");
            return;
        }

        // Verifica se as nações estão em aliança (não podem guerrear)
        if (areAllied(attackerNation, targetNation)) {
            leader.sendMessage("§cVocê não pode declarar guerra a uma nação aliada!");
            return;
        }

        // Verifica se as nações estão em trégua
        if (allianceManager.isInTruce(attackerNation, targetNation)) {
            leader.sendMessage("§cVocê não pode declarar guerra enquanto houver uma trégua com " + targetNation + "!");
            return;
        }

        // Verifica cooldown (24 horas contra a mesma nação)
        if (hasRecentWar(attackerNation, targetNation)) {
            leader.sendMessage("§cVocê deve esperar 24 horas para declarar guerra novamente contra esta nação!");
            return;
        }

        // Verifica custo inicial (configurável, com redução por aliança)
        double initialCost = plugin.getConfig().getDouble("war-initial-cost", 5000.0);
        double reduction = allianceManager.getWarCostReduction(attackerNation);
        double finalCost = initialCost * (1 - reduction);
        double treasury = getNationTreasury(attackerNation);
        if (treasury < finalCost) {
            leader.sendMessage("§cO tesouro da sua nação não tem dólares suficientes! Custo: " + finalCost + " dólares (redução de " + (reduction * 100) + "% por aliança).");
            return;
        }

        // Remove o custo inicial do tesouro
        removeFromNationTreasury(attackerNation, finalCost);

        // Registra a guerra no banco de dados e memória
        long startTime = System.currentTimeMillis();
        activeWars.computeIfAbsent(attackerNation, k -> new HashMap<>()).put(targetNation, startTime);

        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO wars (attacker, defender, start_time) VALUES (?, ?, ?)");
            stmt.setString(1, attackerNation);
            stmt.setString(2, targetNation);
            stmt.setLong(3, startTime);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao registrar guerra: " + e.getMessage());
            leader.sendMessage("§cErro ao declarar guerra. Contate um administrador!");
            return;
        }

        // Notifica ambas as nações
        notifyWarStart(attackerNation, targetNation);
        leader.sendMessage("§aGuerra declarada contra " + targetNation + " por " + finalCost + " dólares (redução de " + (reduction * 100) + "% por aliança).");
    }

    // Método para iniciar a conquista de um território
    public void startConquest(Player player, String targetNation) {
        String attackerNation = nationManager.getPlayerNation(player);
        if (attackerNation == null) {
            player.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!isWarActive(attackerNation, targetNation)) {
            player.sendMessage("§cNão há uma guerra ativa contra esta nação!");
            return;
        }

        player.sendMessage("§eUse a pá de madeira para selecionar dois pontos do território a conquistar (mínimo " + plugin.getConfig().getInt("conquest-min-size", 5) + "x" + plugin.getConfig().getInt("conquest-min-size", 5) + ")!");
        conquestSelection.put(player.getUniqueId(), null); // Inicia a seleção
    }

    // Método para processar a seleção de conquista
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getItem() != null && event.getItem().getType() == Material.WOODEN_SHOVEL) {
            Location loc = event.getClickedBlock().getLocation();
            UUID uuid = player.getUniqueId();

            if (conquestSelection.containsKey(uuid)) {
                Location firstPoint = conquestSelection.get(uuid);
                if (firstPoint == null) {
                    conquestSelection.put(uuid, loc);
                    player.sendMessage("§aPrimeiro ponto marcado em X: " + loc.getBlockX() + ", Z: " + loc.getBlockZ());
                    showParticles(loc, player); // Mostra partículas no primeiro ponto
                } else {
                    conquestSelection.remove(uuid);
                    beginConquest(player, firstPoint, loc);
                }
                event.setCancelled(true); // Cancela a interação com o bloco
            }
        }
    }

    // Método para iniciar a conquista
    private void beginConquest(Player player, Location loc1, Location loc2) {
        String attackerNation = nationManager.getPlayerNation(player);
        if (attackerNation == null) {
            player.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        // Calcula as coordenadas da área de conquista (mínimo configurável)
        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());

        int minSize = plugin.getConfig().getInt("conquest-min-size", 5);
        if ((maxX - minX + 1) < minSize || (maxZ - minZ + 1) < minSize) {
            player.sendMessage("§cA área de conquista deve ter pelo menos " + minSize + "x" + minSize + " blocos!");
            return;
        }

        String world = loc1.getWorld().getName();
        String defenderNation = getNationAtLocation(loc1); // Verifica qual nação possui o terreno

        if (defenderNation == null || !isWarActive(attackerNation, defenderNation)) {
            player.sendMessage("§cNão há uma guerra ativa contra a nação que possui este território!");
            return;
        }

        // Inicia o timer de conquista (duração configurável)
        startConquestTimer(player, attackerNation, defenderNation, minX, maxX, minZ, maxZ, world);
    }

    // Método para iniciar o timer de conquista
    private void startConquestTimer(Player player, String attackerNation, String defenderNation, int minX, int maxX, int minZ, int maxZ, String world) {
        BossBar bossBar = Bukkit.createBossBar("Conquistando " + defenderNation + " (" + (maxX - minX + 1) + "x" + (maxZ - minZ + 1) + ")", BarColor.RED, BarStyle.SOLID);
        warBossBars.put(attackerNation + "-" + defenderNation, bossBar);

        // Adiciona jogadores das nações à bossbar
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            String nation = nationManager.getPlayerNation(p);
            if (nation != null && (nation.equals(attackerNation) || nation.equals(defenderNation))) {
                bossBar.addPlayer(p);
            }
        }

        int totalBlocks = (maxX - minX + 1) * (maxZ - minZ + 1);
        new BukkitRunnable() {
            double progress = 0.0;
            final long startTime = System.currentTimeMillis();
            final long duration = plugin.getConfig().getInt("conquest-duration", 5) * 60 * 1000; // Duração configurável em minutos

            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                if (currentTime - startTime >= duration) {
                    bossBar.setProgress(1.0);
                    endConquest(player, attackerNation, defenderNation, minX, maxX, minZ, maxZ, world, true);
                    bossBar.removeAll();
                    warBossBars.remove(attackerNation + "-" + defenderNation);
                    cancel();
                    return;
                }

                // Calcula progresso baseado em jogadores na área
                int attackersInArea = countPlayersInArea(attackerNation, minX, maxX, minZ, maxZ, world);
                int defendersInArea = countPlayersInArea(defenderNation, minX, maxX, minZ, maxZ, world);
                double progressIncrement = (attackersInArea - defendersInArea) * 0.0001; // Ajuste para balanceamento
                progress = Math.max(0.0, Math.min(1.0, progress + progressIncrement));

                bossBar.setProgress(progress);
                showParticles(new Location(Bukkit.getWorld(world), minX, 0, minZ), player); // Mostra partículas durante a conquista
            }
        }.runTaskTimer(plugin, 0L, 20L); // Atualiza a cada segundo (20 ticks)
    }

    // Método para contar jogadores de uma nação na área de conquista
    private int countPlayersInArea(String nation, int minX, int maxX, int minZ, int maxZ, String world) {
        int count = 0;
        World w = Bukkit.getWorld(world);
        if (w == null) return 0;

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Location loc = p.getLocation();
            if (loc.getWorld().getName().equals(world) &&
                    loc.getBlockX() >= minX && loc.getBlockX() <= maxX &&
                    loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ &&
                    nationManager.getPlayerNation(p).equals(nation)) {
                count++;
            }
        }
        return count;
    }

    // Método para finalizar a conquista
    private void endConquest(Player player, String attackerNation, String defenderNation, int minX, int maxX, int minZ, int maxZ, String world, boolean success) {
        if (success) {
            // Transfere o território para a nação atacante
            transferTerritory(attackerNation, defenderNation, minX, maxX, minZ, maxZ, world);
            notifyConquestSuccess(attackerNation, defenderNation, minX, maxX, minZ, maxZ);
        } else {
            player.sendMessage("§cA conquista falhou por falta de presença!");
        }

        // Remove a guerra se não houver mais territórios a conquistar
        if (!hasTerritories(defenderNation)) {
            endWar(attackerNation, defenderNation);
        }
    }

    // Método para transferir o território
    private void transferTerritory(String attackerNation, String defenderNation, int minX, int maxX, int minZ, int maxZ, String world) {
        try {
            // Remove o claim da nação defendida
            PreparedStatement deleteStmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM claims WHERE nation = ? AND world = ? AND min_x >= ? AND max_x <= ? AND min_z >= ? AND max_z <= ?");
            deleteStmt.setString(1, defenderNation);
            deleteStmt.setString(2, world);
            deleteStmt.setInt(3, minX);
            deleteStmt.setInt(4, maxX);
            deleteStmt.setInt(5, minZ);
            deleteStmt.setInt(6, maxZ);
            deleteStmt.executeUpdate();

            // Adiciona o claim para a nação atacante
            PreparedStatement insertStmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO claims (nation, world, min_x, max_x, min_z, max_z) VALUES (?, ?, ?, ?, ?, ?)");
            insertStmt.setString(1, attackerNation);
            insertStmt.setString(2, world);
            insertStmt.setInt(3, minX);
            insertStmt.setInt(4, maxX);
            insertStmt.setInt(5, minZ);
            insertStmt.setInt(6, maxZ);
            insertStmt.executeUpdate();

            plugin.getLogger().info("§aTerritório transferido de " + defenderNation + " para " + attackerNation + "!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao transferir território: " + e.getMessage());
        }
    }

    // Método para verificar se uma nação possui territórios
    private boolean hasTerritories(String nation) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM claims WHERE nation = ?");
            stmt.setString(1, nation);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao verificar territórios: " + e.getMessage());
        }
        return false;
    }

    // Método para verificar se há uma guerra ativa
    private boolean isWarActive(String attacker, String defender) {
        return activeWars.containsKey(attacker) && activeWars.get(attacker).containsKey(defender);
    }

    // Método para verificar cooldown de 24 horas contra a mesma nação
    private boolean hasRecentWar(String attacker, String defender) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT start_time FROM wars WHERE attacker = ? AND defender = ? ORDER BY start_time DESC LIMIT 1");
            stmt.setString(1, attacker);
            stmt.setString(2, defender);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long lastWarTime = rs.getLong("start_time");
                return System.currentTimeMillis() - lastWarTime < 24 * 60 * 60 * 1000; // 24 horas
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao verificar cooldown de guerra: " + e.getMessage());
        }
        return false;
    }

    // Método para finalizar uma guerra
    private void endWar(String attacker, String defender) {
        activeWars.get(attacker).remove(defender);
        if (activeWars.get(attacker).isEmpty()) {
            activeWars.remove(attacker);
        }

        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM wars WHERE attacker = ? AND defender = ?");
            stmt.setString(1, attacker);
            stmt.setString(2, defender);
            stmt.executeUpdate();

            plugin.getLogger().info("§cGuerra entre " + attacker + " e " + defender + " terminou!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao finalizar guerra: " + e.getMessage());
        }
    }

    // Método para notificar o início da guerra
    private void notifyWarStart(String attacker, String defender) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            String nation = nationManager.getPlayerNation(p);
            if (nation != null && (nation.equals(attacker) || nation.equals(defender))) {
                p.sendMessage("§cA guerra entre " + attacker + " e " + defender + " começou!");
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.0f); // Som de alerta
            }
        }
    }

    // Método para notificar sucesso na conquista
    private void notifyConquestSuccess(String attackerNation, String defenderNation, int minX, int maxX, int minZ, int maxZ) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            String nation = nationManager.getPlayerNation(p);
            if (nation != null && (nation.equals(attackerNation) || nation.equals(defenderNation))) {
                p.sendMessage("§aA nação " + attackerNation + " conquistou um território de " + defenderNation + " em X: " + minX + "-" + maxX + ", Z: " + minZ + "-" + maxZ + "!");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f); // Som de vitória (corrigido)
            }
        }
    }

    // Método para mostrar partículas durante a conquista
    private void showParticles(Location loc, Player player) {
        World world = loc.getWorld();
        if (world == null) return;

        Particle particle = Particle.valueOf(plugin.getConfig().getString("particle-color-war", "GLOWSTONE_DUST"));
        world.spawnParticle(particle, loc, 50, 5.0, 5.0, 5.0, 0.1);
        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f); // Som de partículas
    }

    // Método para iniciar a manutenção das guerras (custos por hora)
    private void startWarMaintenance() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (String attacker : activeWars.keySet()) {
                    for (String defender : activeWars.get(attacker).keySet()) {
                        double maintenanceCost = plugin.getConfig().getDouble("war-maintenance-cost", 1000.0);
                        double reduction = allianceManager.getWarCostReduction(attacker);
                        double finalCost = maintenanceCost * (1 - reduction);
                        if (getNationTreasury(attacker) < finalCost) {
                            endWar(attacker, defender);
                            notifyWarEndDueToFunds(attacker, defender);
                            continue;
                        }
                        removeFromNationTreasury(attacker, finalCost);
                        notifyMaintenanceCost(attacker, defender, finalCost);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20 * 60 * 60L); // A cada hora (3600 segundos)
    }

    // Método para notificar custo de manutenção
    private void notifyMaintenanceCost(String attacker, String defender, double cost) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            String nation = nationManager.getPlayerNation(p);
            if (nation != null && nation.equals(attacker)) {
                p.sendMessage("§eNo tempo em que você esteve ausente, a guerra contra " + defender + " lhe custou " + cost + " dólares!");
            }
        }
    }

    // Método para notificar fim da guerra por falta de fundos
    private void notifyWarEndDueToFunds(String attacker, String defender) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            String nation = nationManager.getPlayerNation(p);
            if (nation != null && (nation.equals(attacker) || nation.equals(defender))) {
                p.sendMessage("§cA guerra entre " + attacker + " e " + defender + " terminou por falta de fundos no tesouro de " + attacker + "!");
            }
        }
    }

    // Método para verificar se duas nações estão aliadas
    private boolean areAllied(String nation1, String nation2) {
        for (String alliance : allianceManager.getAlliances().keySet()) {
            if (allianceManager.isNationInAlliance(nation1, alliance) && allianceManager.isNationInAlliance(nation2, alliance)) {
                return true;
            }
        }
        return false;
    }

    // Método para obter a nação que possui um território
    private String getNationAtLocation(Location loc) {
        return claimManager.getClaimNation(loc);
    }

    // Método para obter o tesouro de uma nação
    private double getNationTreasury(String nation) {
        return ((MeuPrimeiroPlugin) plugin).getBankManager().getNationTreasury(nation);
    }

    // Método para remover do tesouro de uma nação
    private void removeFromNationTreasury(String nation, double amount) {
        ((MeuPrimeiroPlugin) plugin).getBankManager().removeFromNationTreasury(nation, amount);
    }

    // Método para verificar se uma nação existe
    private boolean nationExists(String nationName) {
        return nationManager.nationExists(nationName);
    }

    // Método para obter guerras ativas (public para MapManager)
    public Map<String, Map<String, Long>> getActiveWars() {
        return activeWars;
    }

    // Método para propor rendição
    public void surrender(Player leader, String targetNation) {
        String attackerNation = nationManager.getPlayerNation(leader);
        if (attackerNation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas o líder da nação pode se render!");
            return;
        }

        if (!isWarActive(attackerNation, targetNation)) {
            leader.sendMessage("§cNão há uma guerra ativa contra esta nação!");
            return;
        }

        // Adiciona a proposta de rendição com timestamp (expira em 5 minutos)
        long expirationTime = System.currentTimeMillis() + (5 * 60 * 1000); // 5 minutos
        surrenderProposals.computeIfAbsent(attackerNation, k -> new HashMap<>()).put(targetNation, expirationTime);

        // Notifica o líder da nação alvo
        Player targetLeader = Bukkit.getPlayer(nationManager.getNationLeaders().get(targetNation));
        if (targetLeader != null) {
            targetLeader.sendMessage("§eA nação " + attackerNation + " propôs uma rendição na guerra contra você!");
            targetLeader.sendMessage("§eUse /nation surrender accept " + attackerNation + " para aceitar ou espere 5 minutos para expirar.");
        }
        leader.sendMessage("§aProposta de rendição enviada para " + targetNation + "!");

        // Remove proposta expirada após 5 minutos
        new BukkitRunnable() {
            @Override
            public void run() {
                if (surrenderProposals.containsKey(attackerNation) && surrenderProposals.get(attackerNation).containsKey(targetNation)) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime > surrenderProposals.get(attackerNation).get(targetNation)) {
                        surrenderProposals.get(attackerNation).remove(targetNation);
                        if (surrenderProposals.get(attackerNation).isEmpty()) {
                            surrenderProposals.remove(attackerNation);
                        }
                        if (targetLeader != null) {
                            targetLeader.sendMessage("§cA proposta de rendição de " + attackerNation + " expirou!");
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 20 * 60 * 5L); // 5 minutos (300 segundos)
    }

    // Método para aceitar rendição
    public void acceptSurrender(Player leader, String proposingNation) {
        String defenderNation = nationManager.getPlayerNation(leader);
        if (defenderNation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas o líder da nação pode aceitar rendições!");
            return;
        }

        if (!isWarActive(proposingNation, defenderNation)) {
            leader.sendMessage("§cNão há uma guerra ativa com esta nação!");
            return;
        }

        if (!surrenderProposals.containsKey(proposingNation) || !surrenderProposals.get(proposingNation).containsKey(defenderNation)) {
            leader.sendMessage("§cNão há uma proposta de rendição pendente desta nação!");
            return;
        }

        long expiration = surrenderProposals.get(proposingNation).get(defenderNation);
        if (System.currentTimeMillis() > expiration) {
            leader.sendMessage("§cA proposta de rendição de " + proposingNation + " expirou!");
            surrenderProposals.get(proposingNation).remove(defenderNation);
            if (surrenderProposals.get(proposingNation).isEmpty()) {
                surrenderProposals.remove(proposingNation);
            }
            return;
        }

        // Finaliza a guerra sem cobranças, mantendo territórios conquistados
        endWar(proposingNation, defenderNation);

        // Notifica ambas as nações
        leader.sendMessage("§aVocê aceitou a rendição de " + proposingNation + "! A guerra terminou.");
        Player proposerLeader = Bukkit.getPlayer(nationManager.getNationLeaders().get(proposingNation));
        if (proposerLeader != null) {
            proposerLeader.sendMessage("§eA nação " + defenderNation + " aceitou sua rendição! A guerra terminou.");
        }

        // Remove a proposta após aceitação
        surrenderProposals.get(proposingNation).remove(defenderNation);
        if (surrenderProposals.get(proposingNation).isEmpty()) {
            surrenderProposals.remove(proposingNation);
        }
    }
}