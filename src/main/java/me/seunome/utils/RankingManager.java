package me.seunome.utils;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.seunome.claims.ClaimManager;
import me.seunome.economy.EconomyManager;
import me.seunome.geopolitics.NationManager;
import me.seunome.geopolitics.WarManager;

public class RankingManager implements CommandExecutor {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final NationManager nationManager;
    private final ClaimManager claimManager;
    private final EconomyManager economyManager;
    private final WarManager warManager;

    // Construtor para receber a instância do plugin e gerenciadores
    public RankingManager(JavaPlugin plugin, DatabaseManager databaseManager, NationManager nationManager, ClaimManager claimManager, EconomyManager economyManager, WarManager warManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.nationManager = nationManager;
        this.claimManager = claimManager;
        this.economyManager = economyManager;
        this.warManager = warManager;
        plugin.getCommand("ranking").setExecutor(this); // Registra este executor para o comando /ranking
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando só pode ser usado por jogadores!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage("§eUse: /ranking [size|treasury|victories]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "size":
                showSizeRanking(player);
                break;
            case "treasury":
                showTreasuryRanking(player);
                break;
            case "victories":
                showVictoriesRanking(player);
                break;
            default:
                player.sendMessage("§cCategoria inválida! Use: /ranking [size|treasury|victories]");
                return true;
        }
        return true;
    }

    // Método para mostrar ranking por tamanho (número de claims)
    private void showSizeRanking(Player player) {
        List<Map.Entry<String, Integer>> rankings = getSizeRankings();
        player.sendMessage("§aRanking por Tamanho (Claims):");
        int rank = 1;
        for (Map.Entry<String, Integer> entry : rankings) {
            player.sendMessage("§e" + rank + ". " + entry.getKey() + ": " + entry.getValue() + " claims");
            rank++;
        }
    }

    // Método para mostrar ranking por tesouro (saldo em dólares)
    private void showTreasuryRanking(Player player) {
        List<Map.Entry<String, Double>> rankings = getTreasuryRankings();
        player.sendMessage("§aRanking por Tesouro:");
        int rank = 1;
        for (Map.Entry<String, Double> entry : rankings) {
            player.sendMessage("§e" + rank + ". " + entry.getKey() + ": " + String.format("%.2f", entry.getValue()) + " dólares");
            rank++;
        }
    }

    // Método para mostrar ranking por vitórias em guerras
    private void showVictoriesRanking(Player player) {
        List<Map.Entry<String, Integer>> rankings = getVictoriesRankings();
        player.sendMessage("§aRanking por Vitórias em Guerras:");
        int rank = 1;
        for (Map.Entry<String, Integer> entry : rankings) {
            player.sendMessage("§e" + rank + ". " + entry.getKey() + ": " + entry.getValue() + " vitórias");
            rank++;
        }
    }

    // Método para obter ranking por tamanho
    private List<Map.Entry<String, Integer>> getSizeRankings() {
        Map<String, Integer> sizeMap = new HashMap<>();
        for (String nation : nationManager.getNationLeaders().keySet()) {
            int claimCount = countClaims(nation);
            sizeMap.put(nation, claimCount);
        }
        return sortRankings(sizeMap);
    }

    // Método para obter ranking por tesouro
    private List<Map.Entry<String, Double>> getTreasuryRankings() {
        Map<String, Double> treasuryMap = new HashMap<>();
        for (String nation : nationManager.getNationLeaders().keySet()) {
            double treasury = getNationTreasury(nation);
            treasuryMap.put(nation, treasury);
        }
        return sortTreasuryRankings(treasuryMap);
    }

    // Método para obter ranking por vitórias
    private List<Map.Entry<String, Integer>> getVictoriesRankings() {
        Map<String, Integer> victoriesMap = new HashMap<>();
        for (String nation : nationManager.getNationLeaders().keySet()) {
            int victories = countVictories(nation);
            victoriesMap.put(nation, victories);
        }
        return sortRankings(victoriesMap);
    }

    // Método para contar o número de claims de uma nação
    private int countClaims(String nation) {
        int count = 0;
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM claims WHERE nation = ?");
            stmt.setString(1, nation);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                count = rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao contar claims: " + e.getMessage());
        }
        return count;
    }

    // Método para obter o tesouro de uma nação
    private double getNationTreasury(String nation) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT treasury FROM nations WHERE name = ?");
            stmt.setString(1, nation);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getDouble("treasury");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao obter tesouro: " + e.getMessage());
        }
        return 0.0;
    }

    // Método para contar o número de vitórias em guerras de uma nação
    private int countVictories(String nation) {
        int victories = 0;
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM wars WHERE (attacker = ? AND defender NOT IN (SELECT nation FROM claims WHERE nation = ?)) OR " +
                    "(defender = ? AND attacker NOT IN (SELECT nation FROM claims WHERE nation = ?))");
            stmt.setString(1, nation);
            stmt.setString(2, nation);
            stmt.setString(3, nation);
            stmt.setString(4, nation);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                victories = rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao contar vitórias: " + e.getMessage());
        }
        return victories;
    }

    // Método para ordenar rankings (descendente por valor)
    private List<Map.Entry<String, Integer>> sortRankings(Map<String, Integer> map) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue()));
        return list;
    }

    // Método para ordenar rankings de tesouro (descendente por valor)
    private List<Map.Entry<String, Double>> sortTreasuryRankings(Map<String, Double> map) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(map.entrySet());
        list.sort((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue()));
        return list;
    }
}