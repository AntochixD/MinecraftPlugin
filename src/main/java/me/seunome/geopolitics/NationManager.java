package me.seunome.geopolitics;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.claims.ClaimManager;
import me.seunome.economy.EconomyManager;
import me.seunome.utils.DatabaseManager;
import me.seunome.utils.PermissionManager;

public class NationManager implements Listener {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final EconomyManager economyManager;
    private final ClaimManager claimManager;
    private final PermissionManager permissionManager; // Novo campo para PermissionManager
    private final HashMap<UUID, String> playerNations = new HashMap<>(); // Armazena a nação de cada jogador
    private final HashMap<String, UUID> nationLeaders = new HashMap<>(); // Armazena o líder de cada nação
    private final HashMap<UUID, Map<String, Long>> playerInvites = new HashMap<>(); // Armazena convites de nação
    private final CityManager cityManager; // CityManager existente
    private final WarManager warManager; // WarManager existente
    private final AllianceManager allianceManager; // AllianceManager existente

    // Construtor para receber a instância do plugin e gerenciadores
    public NationManager(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.economyManager = ((MeuPrimeiroPlugin) plugin).getEconomyManager();
        this.claimManager = ((MeuPrimeiroPlugin) plugin).getClaimManager();
        this.permissionManager = ((MeuPrimeiroPlugin) plugin).getPermissionManager(); // Inicializa PermissionManager
        this.cityManager = new CityManager(plugin, databaseManager, this, claimManager); // Mantém CityManager
        this.warManager = new WarManager(plugin, databaseManager, this, claimManager); // Mantém WarManager
        this.allianceManager = new AllianceManager(plugin, databaseManager, this); // Mantém AllianceManager
        plugin.getServer().getPluginManager().registerEvents(this, plugin); // Registra este listener

        // Carrega nações e jogadores ao iniciar
        loadNations();
    }

    // Método para criar uma nação
    public void createNation(Player player, String nationName) {
        UUID playerUUID = player.getUniqueId();
        double cost = plugin.getConfig().getDouble("nation-create-cost", 1000.0);

        if (economyManager.getBalance(player) < cost) {
            player.sendMessage("§cVocê não tem dólares suficientes! Custo: " + cost + " dólares.");
            return;
        }

        if (playerNations.containsKey(playerUUID)) {
            player.sendMessage("§cVocê já pertence a uma nação!");
            return;
        }

        if (nationExists(nationName)) {
            player.sendMessage("§cEsta nação já existe!");
            return;
        }

        // Remove o custo do jogador
        economyManager.removeBalance(player, cost);

        // Salva a nação no banco de dados
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO nations (name, leader, treasury) VALUES (?, ?, ?)");
            stmt.setString(1, nationName);
            stmt.setString(2, playerUUID.toString());
            stmt.setDouble(3, 0.0); // Tesouro inicial
            stmt.executeUpdate();

            // Adiciona o jogador como líder e membro
            playerNations.put(playerUUID, nationName);
            nationLeaders.put(nationName, playerUUID);

            player.sendMessage("§aNação " + nationName + " criada com sucesso por " + cost + " dólares!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao criar nação: " + e.getMessage());
            player.sendMessage("§cErro ao criar nação. Contate um administrador!");
        }
    }

    // Método para convidar um jogador para a nação
    public void invitePlayer(Player inviter, Player target, String nationName) {
        if (!isNationLeader(inviter) || !playerNations.get(inviter.getUniqueId()).equals(nationName)) {
            inviter.sendMessage("§cApenas o líder da nação pode convidar membros!");
            return;
        }

        if (playerNations.containsKey(target.getUniqueId())) {
            inviter.sendMessage("§cEste jogador já pertence a uma nação!");
            return;
        }

        if (!nationExists(nationName)) {
            inviter.sendMessage("§cEsta nação não existe!");
            return;
        }

        // Adiciona o convite com timestamp (tempo configurável em minutos)
        long expirationTime = System.currentTimeMillis() + (plugin.getConfig().getInt("nation-invite-expire", 5) * 60 * 1000);
        playerInvites.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>()).put(nationName, expirationTime);

        // Notifica o jogador
        target.sendMessage("§e" + inviter.getName() + " te convidou para a nação " + nationName + "!");
        target.sendMessage("§eUse /nation invite accept " + nationName + " para aceitar ou /nation invite deny " + nationName + " para recusar dentro de " + (expirationTime - System.currentTimeMillis()) / 1000 / 60 + " minutos.");
        inviter.sendMessage("§aConvite enviado para " + target.getName() + "!");

        // Remove convite expirado após o tempo configurável
        new BukkitRunnable() {
            @Override
            public void run() {
                if (playerInvites.containsKey(target.getUniqueId())) {
                    if (playerInvites.get(target.getUniqueId()).containsKey(nationName)) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime > playerInvites.get(target.getUniqueId()).get(nationName)) {
                            playerInvites.get(target.getUniqueId()).remove(nationName);
                            if (playerInvites.get(target.getUniqueId()).isEmpty()) {
                                playerInvites.remove(target.getUniqueId());
                            }
                            target.sendMessage("§cO convite para a nação " + nationName + " expirou!");
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 20 * 60 * plugin.getConfig().getInt("nation-invite-expire", 5) * 1L);
    }

    // Método para aceitar convite
    public void acceptInvite(Player player, String nationName) {
        UUID playerUUID = player.getUniqueId();

        if (!nationExists(nationName)) {
            player.sendMessage("§cEsta nação não existe!");
            return;
        }

        if (playerNations.containsKey(playerUUID)) {
            player.sendMessage("§cVocê já pertence a uma nação!");
            return;
        }

        if (!playerInvites.containsKey(playerUUID) || !playerInvites.get(playerUUID).containsKey(nationName)) {
            player.sendMessage("§cVocê não tem um convite pendente para esta nação!");
            return;
        }

        if (System.currentTimeMillis() > playerInvites.get(playerUUID).get(nationName)) {
            player.sendMessage("§cO convite para a nação " + nationName + " expirou!");
            playerInvites.get(playerUUID).remove(nationName);
            if (playerInvites.get(playerUUID).isEmpty()) {
                playerInvites.remove(playerUUID);
            }
            return;
        }

        playerNations.put(playerUUID, nationName);
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO nation_members (nation, player_uuid) VALUES (?, ?)");
            stmt.setString(1, nationName);
            stmt.setString(2, playerUUID.toString());
            stmt.executeUpdate();

            player.sendMessage("§aVocê entrou na nação " + nationName + "!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao aceitar convite: " + e.getMessage());
            player.sendMessage("§cErro ao entrar na nação. Contate um administrador!");
        }

        // Remove o convite após aceitação
        playerInvites.get(playerUUID).remove(nationName);
        if (playerInvites.get(playerUUID).isEmpty()) {
            playerInvites.remove(playerUUID);
        }
    }

    // Método para recusar convite
    public void denyInvite(Player player, String nationName) {
        UUID playerUUID = player.getUniqueId();

        if (!playerInvites.containsKey(playerUUID) || !playerInvites.get(playerUUID).containsKey(nationName)) {
            player.sendMessage("§cVocê não tem um convite pendente para esta nação!");
            return;
        }

        player.sendMessage("§aVocê recusou o convite para a nação " + nationName + "!");

        // Remove o convite após recusa
        playerInvites.get(playerUUID).remove(nationName);
        if (playerInvites.get(playerUUID).isEmpty()) {
            playerInvites.remove(playerUUID);
        }
    }

    // Método para remover um jogador da nação (só líder)
    public void kickPlayer(Player leader, Player target) {
        UUID leaderUUID = leader.getUniqueId();
        UUID targetUUID = target.getUniqueId();

        if (!isNationLeader(leader)) {
            leader.sendMessage("§cApenas o líder da nação pode remover membros!");
            return;
        }

        String leaderNation = playerNations.get(leaderUUID);
        String targetNation = playerNations.get(targetUUID);

        if (leaderNation == null || !leaderNation.equals(targetNation)) {
            leader.sendMessage("§cEste jogador não pertence à sua nação!");
            return;
        }

        if (nationLeaders.get(leaderNation).equals(targetUUID)) {
            leader.sendMessage("§cVocê não pode remover o líder da nação!");
            return;
        }

        playerNations.remove(targetUUID);
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM nation_members WHERE nation = ? AND player_uuid = ?");
            stmt.setString(1, leaderNation);
            stmt.setString(2, targetUUID.toString());
            stmt.executeUpdate();

            target.sendMessage("§cVocê foi removido da nação " + leaderNation + " por " + leader.getName() + "!");
            leader.sendMessage("§aVocê removeu " + target.getName() + " da nação " + leaderNation + "!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao remover jogador: " + e.getMessage());
            leader.sendMessage("§cErro ao remover jogador. Contate um administrador!");
        }
    }

    // Método para verificar se uma nação existe
    public boolean nationExists(String nationName) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM nations WHERE name = ?");
            stmt.setString(1, nationName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao verificar nação: " + e.getMessage());
        }
        return false;
    }

    // Método para verificar se o jogador é líder da nação
    public boolean isNationLeader(Player player) {
        UUID playerUUID = player.getUniqueId();
        String nation = playerNations.get(playerUUID);
        if (nation == null) return false;
        return nationLeaders.get(nation) != null && nationLeaders.get(nation).equals(playerUUID);
    }

    // Método para obter a nação de um jogador
    public String getPlayerNation(Player player) {
        return playerNations.get(player.getUniqueId());
    }

    // Método para obter a nação de um OfflinePlayer
    public String getPlayerNation(OfflinePlayer player) {
        return playerNations.get(player.getUniqueId());
    }

    // Método para obter os líderes das nações
    public HashMap<String, UUID> getNationLeaders() {
        return nationLeaders;
    }

    // Método para acessar playerNations
    public Map<UUID, String> getPlayerNations() {
        return playerNations;
    }

    // Método para carregar nações e membros do banco de dados ao iniciar
    private void loadNations() {
        try {
            // Carrega nações
            PreparedStatement nationStmt = databaseManager.getConnection().prepareStatement(
                    "SELECT name, leader, treasury FROM nations");
            ResultSet nationRs = nationStmt.executeQuery();
            while (nationRs.next()) {
                String nationName = nationRs.getString("name");
                String leaderUUID = nationRs.getString("leader");
                double treasury = nationRs.getDouble("treasury");
                nationLeaders.put(nationName, UUID.fromString(leaderUUID));
            }

            // Carrega membros
            PreparedStatement memberStmt = databaseManager.getConnection().prepareStatement(
                    "SELECT nation, player_uuid FROM nation_members");
            ResultSet memberRs = memberStmt.executeQuery();
            while (memberRs.next()) {
                String nation = memberRs.getString("nation");
                UUID playerUUID = UUID.fromString(memberRs.getString("player_uuid"));
                playerNations.put(playerUUID, nation);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao carregar nações: " + e.getMessage());
        }
    }

    // Método para dissolver uma nação ou transferir liderança ao sair
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (isNationLeader(player)) {
            String nation = getPlayerNation(player);
            if (nation != null) {
                // Verifica se há outros membros para transferir liderança
                UUID newLeader = findNextLeader(nation, playerUUID);
                if (newLeader != null) {
                    // Transfere liderança para o próximo membro
                    nationLeaders.put(nation, newLeader);
                    Player newLeaderPlayer = plugin.getServer().getPlayer(newLeader);
                    if (newLeaderPlayer != null) {
                        newLeaderPlayer.sendMessage("§aVocê se tornou o novo líder da nação " + nation + "!");
                    }

                    try {
                        PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                                "UPDATE nations SET leader = ? WHERE name = ?");
                        stmt.setString(1, newLeader.toString());
                        stmt.setString(2, nation);
                        stmt.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().severe("§cErro ao transferir liderança: " + e.getMessage());
                    }
                } else {
                    // Dissolve a nação se não houver outros membros
                    dissolveNation(nation);
                }
            }
        }

        // Remove o jogador da nação se não for líder
        playerNations.remove(playerUUID);
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM nation_members WHERE player_uuid = ?");
            stmt.setString(1, playerUUID.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao remover jogador da nação: " + e.getMessage());
        }
    }

    // Método para encontrar o próximo líder (primeiro membro da nação)
    private UUID findNextLeader(String nation, UUID currentLeader) {
        for (Map.Entry<UUID, String> entry : playerNations.entrySet()) {
            if (entry.getValue().equals(nation) && !entry.getKey().equals(currentLeader)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // Método para dissolver uma nação
    private void dissolveNation(String nationName) {
        try {
            // Remove a nação e seus membros do banco de dados
            PreparedStatement nationStmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM nations WHERE name = ?");
            nationStmt.setString(1, nationName);
            nationStmt.executeUpdate();

            PreparedStatement memberStmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM nation_members WHERE nation = ?");
            memberStmt.setString(1, nationName);
            memberStmt.executeUpdate();

            // Remove da memória
            nationLeaders.remove(nationName);
            for (UUID uuid : playerNations.keySet()) {
                if (playerNations.get(uuid).equals(nationName)) {
                    Player player = plugin.getServer().getPlayer(uuid);
                    if (player != null) {
                        player.sendMessage("§cSua nação, " + nationName + ", foi dissolvida por falta de membros!");
                    }
                }
            }
            playerNations.entrySet().removeIf(entry -> entry.getValue().equals(nationName));

            plugin.getLogger().info("§cNação " + nationName + " foi dissolvida por falta de membros!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao dissolver nação: " + e.getMessage());
        }
    }

    // Método para criar uma cidade (chamado por /nation city create)
    public void createCity(Player player, String cityName) {
        cityManager.startCitySelection(player);
    }

    // Método para declarar guerra (chamado por /nation war declare)
    public void declareWar(Player leader, String targetNation) {
        warManager.declareWar(leader, targetNation);
    }

    // Método para iniciar conquista (chamado por /conquer)
    public void startConquest(Player player, String targetNation) {
        warManager.startConquest(player, targetNation);
    }

    // Método para criar uma aliança (chamado por /nation ally create)
    public void createAlliance(Player leader, String allianceName) {
        allianceManager.createAlliance(leader, allianceName);
    }

    // Método para convidar uma nação para a aliança (chamado por /nation ally invite)
    public void inviteAlliance(Player leader, String targetNation, String allianceName) {
        allianceManager.inviteNation(leader, targetNation, allianceName);
    }

    // Método para aceitar convite de aliança (chamado por /nation ally accept)
    public void acceptAllianceInvite(Player leader, String allianceName) {
        allianceManager.acceptAllianceInvite(leader, allianceName);
    }

    // Método para recusar convite de aliança (chamado por /nation ally deny)
    public void denyAllianceInvite(Player leader, String allianceName) {
        allianceManager.denyAllianceInvite(leader, allianceName);
    }

    // Método para sair de uma aliança (chamado por /nation ally leave)
    public void leaveAlliance(Player leader, String allianceName) {
        allianceManager.leaveAlliance(leader, allianceName);
    }

    // Método para romper uma aliança (chamado por /nation ally break)
    public void breakAlliance(Player leader, String allianceName) {
        allianceManager.breakAlliance(leader, allianceName);
    }

    // Método para propor trégua (chamado por /nation truce)
    public void proposeTruce(Player leader, String targetNation, long durationHours) {
        allianceManager.proposeTruce(leader, targetNation, durationHours);
    }

    // Método para aceitar trégua (chamado por /nation truce accept)
    public void acceptTruce(Player leader, String proposingNation) {
        allianceManager.acceptTruce(leader, proposingNation);
    }

    // Método para propor rendição (chamado por /nation surrender)
    public void surrender(Player leader, String targetNation) {
        warManager.surrender(leader, targetNation);
    }

    // Método para aceitar rendição (chamado por /nation surrender accept)
    public void acceptSurrender(Player leader, String proposingNation) {
        warManager.acceptSurrender(leader, proposingNation);
    }

    // Método para mostrar partículas dos limites da nação (chamado por /nation show)
    public void showNationBorders(Player player) {
        String nation = getPlayerNation(player);
        if (nation == null) {
            player.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!isNationLeader(player) && !permissionManager.hasPermission(player, "geocraft.nation.member")) {
            player.sendMessage("§cVocê não tem permissão para acessar este comando!");
            return;
        }

        // Obtém todos os claims da nação
        Map<Location, String> claims = ((MeuPrimeiroPlugin) plugin).getClaimManager().getAllClaimLocations();
        for (Map.Entry<Location, String> entry : claims.entrySet()) {
            if (entry.getValue().equals(nation)) {
                Location loc = entry.getKey();
                showParticlesForClaim(loc, player);
            }
        }

        player.sendMessage("§ePartículas dos limites da nação exibidas por 10 segundos!");
    }

    // Método auxiliar para mostrar partículas em um claim
    private void showParticlesForClaim(Location loc, Player player) {
        World world = loc.getWorld();
        if (world == null) return;

        Particle particle = Particle.valueOf(plugin.getConfig().getString("particle-color-claim", "BARRIER"));
        int minX = loc.getBlockX();
        int maxX = minX + 1; // Simplificado para um bloco (ajuste para claims maiores, se necessário)
        int minZ = loc.getBlockZ();
        int maxZ = minZ + 1;

        new BukkitRunnable() {
            int time = 0;
            @Override
            public void run() {
                if (time >= 10 * 20) { // 10 segundos (200 ticks)
                    cancel();
                    return;
                }

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        world.spawnParticle(particle, x, world.getHighestBlockYAt(x, z), z, 5, 0.1, 0.1, 0.1, 0.05);
                    }
                }
                time += 1;
            }
        }.runTaskTimer(plugin, 0L, 1L); // Atualiza a cada tick (1 tick = 0.05 segundos)
    }
}