package me.seunome.geopolitics;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.economy.EconomyManager;
import me.seunome.utils.DatabaseManager;

public class AllianceManager implements Listener {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final NationManager nationManager;
    private final EconomyManager economyManager;
    private final Map<String, HashMap<String, Long>> alliances = new HashMap<>(); // Aliança → {Nação membro → Timestamp de entrada}
    private final Map<UUID, Map<String, Long>> playerAllianceInvites = new HashMap<>(); // Jogador → {Nação:Aliança → Timestamp de expiração}
    private final Map<String, Map<String, Long>> truces = new HashMap<>(); // Nação → {Nação alvo → Timestamp de expiração}

    // Construtor para receber a instância do plugin e gerenciadores
    public AllianceManager(JavaPlugin plugin, DatabaseManager databaseManager, NationManager nationManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.nationManager = nationManager;
        this.economyManager = ((MeuPrimeiroPlugin) plugin).getEconomyManager();
        plugin.getServer().getPluginManager().registerEvents(this, plugin); // Registra este listener

        // Carrega alianças e tréguas do banco de dados ao iniciar
        loadAlliances();
    }

    // Método para carregar alianças e tréguas do banco de dados
    private void loadAlliances() {
        try {
            // Carrega alianças
            PreparedStatement stmtAlliances = databaseManager.getConnection().prepareStatement(
                    "SELECT alliance, nation, join_time FROM alliance_members");
            ResultSet rsAlliances = stmtAlliances.executeQuery();
            while (rsAlliances.next()) {
                String allianceName = rsAlliances.getString("alliance");
                String nation = rsAlliances.getString("nation");
                long joinTime = rsAlliances.getLong("join_time");
                alliances.computeIfAbsent(allianceName, k -> new HashMap<>()).put(nation, joinTime);
            }

            // Carrega tréguas
            PreparedStatement stmtTruces = databaseManager.getConnection().prepareStatement(
                    "SELECT nation1, nation2, expiration FROM nation_truces");
            ResultSet rsTruces = stmtTruces.executeQuery();
            while (rsTruces.next()) {
                String nation1 = rsTruces.getString("nation1");
                String nation2 = rsTruces.getString("nation2");
                long expiration = rsTruces.getLong("expiration");
                truces.computeIfAbsent(nation1, k -> new HashMap<>()).put(nation2, expiration);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao carregar alianças e tréguas: " + e.getMessage());
        }
    }

    // Método para criar uma aliança com 5% do tesouro da nação
    public void createAlliance(Player leader, String allianceName) {
        String nation = nationManager.getPlayerNation(leader);
        if (nation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas o líder da nação pode criar alianças!");
            return;
        }

        if (allianceExists(allianceName)) {
            leader.sendMessage("§cEsta aliança já existe!");
            return;
        }

        // Obtém o tesouro da nação
        double treasury = ((MeuPrimeiroPlugin) plugin).getBankManager().getNationTreasury(nation);
        double cost = treasury * 0.05; // 5% do tesouro

        if (treasury < cost) {
            leader.sendMessage("§cO tesouro da sua nação não tem dólares suficientes! Custo: " + cost + " dólares (5% do tesouro).");
            return;
        }

        // Remove o custo do tesouro nacional
        ((MeuPrimeiroPlugin) plugin).getBankManager().removeFromNationTreasury(nation, cost);

        // Salva a aliança no banco de dados e memória
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO alliances (name, leader_nation) VALUES (?, ?)");
            stmt.setString(1, allianceName);
            stmt.setString(2, nation);
            stmt.executeUpdate();

            alliances.put(allianceName, new HashMap<>());
            alliances.get(allianceName).put(nation, System.currentTimeMillis());

            leader.sendMessage("§aAliança " + allianceName + " criada com sucesso por " + cost + " dólares (5% do tesouro da nação)!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao criar aliança: " + e.getMessage());
            leader.sendMessage("§cErro ao criar aliança. Contate um administrador!");
        }
    }

    // Método para convidar uma nação para a aliança
    public void inviteNation(Player leader, String targetNation, String allianceName) {
        String inviterNation = nationManager.getPlayerNation(leader);
        if (inviterNation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas o líder da nação pode convidar para alianças!");
            return;
        }

        if (!allianceExists(allianceName) || !isNationInAlliance(inviterNation, allianceName)) {
            leader.sendMessage("§cVocê não pertence a esta aliança ou ela não existe!");
            return;
        }

        if (!nationExists(targetNation)) {
            leader.sendMessage("§cEsta nação não existe!");
            return;
        }

        if (isNationInAlliance(targetNation, allianceName)) {
            leader.sendMessage("§cEsta nação já está na aliança!");
            return;
        }

        // Adiciona o convite com timestamp (5 minutos por padrão, configurável)
        long expirationTime = System.currentTimeMillis() + (plugin.getConfig().getInt("alliance-invite-expire", 5) * 60 * 1000);
        UUID leaderUUID = nationManager.getNationLeaders().get(inviterNation);
        Player targetLeader = Bukkit.getPlayer(nationManager.getNationLeaders().get(targetNation));
        if (targetLeader != null) {
            playerAllianceInvites.computeIfAbsent(leaderUUID, k -> new HashMap<>()).put(targetNation + ":" + allianceName, expirationTime);

            // Notifica o líder da nação alvo
            targetLeader.sendMessage("§e" + leader.getName() + " da nação " + inviterNation + " te convidou para a aliança " + allianceName + "!");
            targetLeader.sendMessage("§eUse /nation ally accept " + allianceName + " para aceitar ou /nation ally deny " + allianceName + " para recusar dentro de " + (expirationTime - System.currentTimeMillis()) / 1000 / 60 + " minutos.");
            leader.sendMessage("§aConvite enviado para a nação " + targetNation + "!");
        }

        // Remove convite expirado após o tempo configurável
        new BukkitRunnable() {
            @Override
            public void run() {
                if (playerAllianceInvites.containsKey(leaderUUID)) {
                    if (playerAllianceInvites.get(leaderUUID).containsKey(targetNation + ":" + allianceName)) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime > playerAllianceInvites.get(leaderUUID).get(targetNation + ":" + allianceName)) {
                            playerAllianceInvites.get(leaderUUID).remove(targetNation + ":" + allianceName);
                            if (playerAllianceInvites.get(leaderUUID).isEmpty()) {
                                playerAllianceInvites.remove(leaderUUID);
                            }
                            if (targetLeader != null) {
                                targetLeader.sendMessage("§cO convite para a aliança " + allianceName + " expirou!");
                            }
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 20 * 60 * plugin.getConfig().getInt("alliance-invite-expire", 5) * 1L);
    }

    // Método para aceitar um convite de aliança
    public void acceptAllianceInvite(Player leader, String allianceName) {
        String nation = nationManager.getPlayerNation(leader);
        if (nation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas o líder da nação pode aceitar convites de aliança!");
            return;
        }

        UUID leaderUUID = leader.getUniqueId();
        if (!playerAllianceInvites.containsKey(leaderUUID) || !playerAllianceInvites.get(leaderUUID).containsKey(nation + ":" + allianceName)) {
            leader.sendMessage("§cVocê não tem um convite pendente para esta aliança!");
            return;
        }

        long expirationTime = playerAllianceInvites.get(leaderUUID).get(nation + ":" + allianceName);
        if (System.currentTimeMillis() > expirationTime) {
            leader.sendMessage("§cO convite para a aliança " + allianceName + " expirou!");
            playerAllianceInvites.get(leaderUUID).remove(nation + ":" + allianceName);
            if (playerAllianceInvites.get(leaderUUID).isEmpty()) {
                playerAllianceInvites.remove(leaderUUID);
            }
            return;
        }

        // Adiciona a nação à aliança
        alliances.get(allianceName).put(nation, System.currentTimeMillis());
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO alliance_members (alliance, nation) VALUES (?, ?)");
            stmt.setString(1, allianceName);
            stmt.setString(2, nation);
            stmt.executeUpdate();

            leader.sendMessage("§aSua nação entrou na aliança " + allianceName + "!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao aceitar convite de aliança: " + e.getMessage());
            leader.sendMessage("§cErro ao entrar na aliança. Contate um administrador!");
        }

        // Remove o convite após aceitação
        playerAllianceInvites.get(leaderUUID).remove(nation + ":" + allianceName);
        if (playerAllianceInvites.get(leaderUUID).isEmpty()) {
            playerAllianceInvites.remove(leaderUUID);
        }
    }

    // Método para recusar um convite de aliança
    public void denyAllianceInvite(Player leader, String allianceName) {
        String nation = nationManager.getPlayerNation(leader);
        if (nation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas o líder da nação pode recusar convites de aliança!");
            return;
        }

        UUID leaderUUID = leader.getUniqueId();
        if (!playerAllianceInvites.containsKey(leaderUUID) || !playerAllianceInvites.get(leaderUUID).containsKey(nation + ":" + allianceName)) {
            leader.sendMessage("§cVocê não tem um convite pendente para esta aliança!");
            return;
        }

        leader.sendMessage("§aVocê recusou o convite para a aliança " + allianceName + "!");

        // Remove o convite após recusa
        playerAllianceInvites.get(leaderUUID).remove(nation + ":" + allianceName);
        if (playerAllianceInvites.get(leaderUUID).isEmpty()) {
            playerAllianceInvites.remove(leaderUUID);
        }
    }

    // Método para sair de uma aliança com penalidade de 7% do tesouro
    public void leaveAlliance(Player leader, String allianceName) {
        String nation = nationManager.getPlayerNation(leader);
        if (nation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas o líder da nação pode sair de alianças!");
            return;
        }

        if (!allianceExists(allianceName) || !isNationInAlliance(nation, allianceName)) {
            leader.sendMessage("§cSua nação não pertence a esta aliança!");
            return;
        }

        // Obtém o tesouro da nação
        double treasury = ((MeuPrimeiroPlugin) plugin).getBankManager().getNationTreasury(nation);
        double penalty = treasury * 0.07; // 7% do tesouro como penalidade

        if (treasury < penalty) {
            leader.sendMessage("§cO tesouro da sua nação não tem dólares suficientes para a penalidade! Custo: " + penalty + " dólares (7% do tesouro).");
            return;
        }

        // Remove a penalidade do tesouro nacional
        ((MeuPrimeiroPlugin) plugin).getBankManager().removeFromNationTreasury(nation, penalty);

        // Remove a nação da aliança
        alliances.get(allianceName).remove(nation);
        if (alliances.get(allianceName).isEmpty()) {
            alliances.remove(allianceName);
        }

        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM alliance_members WHERE alliance = ? AND nation = ?");
            stmt.setString(1, allianceName);
            stmt.setString(2, nation);
            stmt.executeUpdate();

            leader.sendMessage("§aSua nação saiu da aliança " + allianceName + " com penalidade de " + penalty + " dólares (7% do tesouro)!");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao sair da aliança: " + e.getMessage());
            leader.sendMessage("§cErro ao sair da aliança. Contate um administrador!");
        }
    }

    // Método para romper uma aliança com penalidade de 7% do tesouro
    public boolean breakAlliance(Player leader, String allianceName) {
        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas líderes de nação podem romper alianças!");
            return false;
        }

        String leaderNation = nationManager.getPlayerNation(leader);
        if (leaderNation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return false;
        }

        if (!alliances.containsKey(allianceName) || !isNationInAlliance(leaderNation, allianceName)) {
            leader.sendMessage("§cSua nação não pertence a esta aliança!");
            return false;
        }

        // Obtém o tesouro da nação
        double treasury = ((MeuPrimeiroPlugin) plugin).getBankManager().getNationTreasury(leaderNation);
        double penalty = treasury * 0.07; // 7% do tesouro como penalidade

        if (treasury < penalty) {
            leader.sendMessage("§cO tesouro da sua nação não tem dólares suficientes para a penalidade! Custo: " + penalty + " dólares (7% do tesouro).");
            return false;
        }

        // Remove a penalidade do tesouro nacional
        ((MeuPrimeiroPlugin) plugin).getBankManager().removeFromNationTreasury(leaderNation, penalty);

        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM alliances WHERE name = ?");
            stmt.setString(1, allianceName);
            stmt.executeUpdate();

            PreparedStatement memberStmt = databaseManager.getConnection().prepareStatement(
                    "DELETE FROM alliance_members WHERE alliance = ?");
            memberStmt.setString(1, allianceName);
            memberStmt.executeUpdate();

            alliances.remove(allianceName);

            leader.sendMessage("§aAliança " + allianceName + " foi rompida com penalidade de " + penalty + " dólares (7% do tesouro)!");
            for (String nation : nationManager.getNationLeaders().keySet()) {
                if (isNationInAlliance(nation, allianceName)) {
                    Player nationLeader = Bukkit.getPlayer(nationManager.getNationLeaders().get(nation));
                    if (nationLeader != null) {
                        nationLeader.sendMessage("§eA aliança " + allianceName + " foi rompida por " + leader.getName() + " com penalidade de " + penalty + " dólares!");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao romper aliança: " + e.getMessage());
            leader.sendMessage("§cErro ao romper aliança. Contate um administrador!");
            return false;
        }
        return true;
    }

    // Método para verificar se uma aliança existe (public para visibilidade)
    public boolean allianceExists(String allianceName) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM alliances WHERE name = ?");
            stmt.setString(1, allianceName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao verificar aliança: " + e.getMessage());
        }
        return false;
    }

    // Método para verificar se uma nação está em uma aliança (public para visibilidade)
    public boolean isNationInAlliance(String nation, String allianceName) {
        return alliances.containsKey(allianceName) && alliances.get(allianceName).containsKey(nation);
    }

    // Método para verificar se uma nação existe (usado em inviteNation, agora public)
    public boolean nationExists(String nationName) {
        return nationManager.nationExists(nationName); // Chama o método de NationManager
    }

    // Método para verificar benefícios de aliança (ex.: redução de custos de guerra)
    public double getWarCostReduction(String nation) {
        for (String alliance : alliances.keySet()) {
            if (isNationInAlliance(nation, alliance)) {
                return plugin.getConfig().getDouble("alliance-war-cost-reduction", 0.25); // 25% de redução por padrão
            }
        }
        return 0.0;
    }

    // Método para obter alianças (public para visibilidade, usado por MapManager e WarManager)
    public Map<String, HashMap<String, Long>> getAlliances() {
        return alliances;
    }

    // Método para verificar se duas nações estão em trégua
    public boolean isInTruce(String nation1, String nation2) {
        if (truces.containsKey(nation1) && truces.get(nation1).containsKey(nation2)) {
            long expiration = truces.get(nation1).get(nation2);
            return System.currentTimeMillis() <= expiration;
        }
        if (truces.containsKey(nation2) && truces.get(nation2).containsKey(nation1)) {
            long expiration = truces.get(nation2).get(nation1);
            return System.currentTimeMillis() <= expiration;
        }
        return false;
    }

    // Método para propor uma trégua
    public boolean proposeTruce(Player leader, String targetNation, long durationHours) {
        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas líderes de nação podem propor tréguas!");
            return false;
        }

        String leaderNation = nationManager.getPlayerNation(leader);
        if (leaderNation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return false;
        }

        if (!nationManager.nationExists(targetNation)) {
            leader.sendMessage("§cA nação " + targetNation + " não existe!");
            return false;
        }

        if (isInTruce(leaderNation, targetNation)) {
            leader.sendMessage("§cJá existe uma trégua com " + targetNation + "!");
            return false;
        }

        double cost = plugin.getConfig().getDouble("truce-proposal-cost", 500.0);
        if (economyManager.getBalance(leader) < cost) {
            leader.sendMessage("§cVocê não tem dólares suficientes! Custo: " + cost + " dólares.");
            return false;
        }

        economyManager.removeBalance(leader, cost);

        long expiration = System.currentTimeMillis() + (durationHours * 60 * 60 * 1000); // Converte horas pra milissegundos
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO nation_truces (nation1, nation2, expiration) VALUES (?, ?, ?)");
            stmt.setString(1, leaderNation);
            stmt.setString(2, targetNation);
            stmt.setLong(3, expiration);
            stmt.executeUpdate();

            truces.computeIfAbsent(leaderNation, k -> new HashMap<>()).put(targetNation, expiration);

            leader.sendMessage("§aTrégua proposta com " + targetNation + " por " + durationHours + " horas, custando " + cost + " dólares!");
            Player targetLeader = Bukkit.getPlayer(nationManager.getNationLeaders().get(targetNation));
            if (targetLeader != null) {
                targetLeader.sendMessage("§eA nação " + leaderNation + " propôs uma trégua com você por " + durationHours + " horas. Aceite com /nation truce accept <" + leaderNation + ">!");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao propor trégua: " + e.getMessage());
            leader.sendMessage("§cErro ao propor trégua. Contate um administrador!");
            return false;
        }
        return true;
    }

    // Método para aceitar uma trégua
    public boolean acceptTruce(Player leader, String proposingNation) {
        if (!nationManager.isNationLeader(leader)) {
            leader.sendMessage("§cApenas líderes de nação podem aceitar tréguas!");
            return false;
        }

        String leaderNation = nationManager.getPlayerNation(leader);
        if (leaderNation == null) {
            leader.sendMessage("§cVocê não pertence a uma nação!");
            return false;
        }

        if (!nationManager.nationExists(proposingNation)) {
            leader.sendMessage("§cA nação " + proposingNation + " não existe!");
            return false;
        }

        if (!truces.containsKey(proposingNation) || !truces.get(proposingNation).containsKey(leaderNation)) {
            leader.sendMessage("§cNão há uma trégua proposta por " + proposingNation + " para sua nação!");
            return false;
        }

        long expiration = truces.get(proposingNation).get(leaderNation);
        if (System.currentTimeMillis() > expiration) {
            leader.sendMessage("§cA trégua proposta por " + proposingNation + " expirou!");
            truces.get(proposingNation).remove(leaderNation);
            if (truces.get(proposingNation).isEmpty()) {
                truces.remove(proposingNation);
            }
            return false;
        }

        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "UPDATE nation_truces SET accepted = 1 WHERE nation1 = ? AND nation2 = ?");
            stmt.setString(1, proposingNation);
            stmt.setString(2, leaderNation);
            stmt.executeUpdate();

            leader.sendMessage("§aTrégua com " + proposingNation + " aceita com sucesso!");
            Player proposerLeader = Bukkit.getPlayer(nationManager.getNationLeaders().get(proposingNation));
            if (proposerLeader != null) {
                proposerLeader.sendMessage("§eA nação " + leaderNation + " aceitou sua trégua!");
            }

            // Torna a trégua simétrica
            truces.computeIfAbsent(leaderNation, k -> new HashMap<>()).put(proposingNation, expiration);
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao aceitar trégua: " + e.getMessage());
            leader.sendMessage("§cErro ao aceitar trégua. Contate um administrador!");
            return false;
        }
        return true;
    }
}