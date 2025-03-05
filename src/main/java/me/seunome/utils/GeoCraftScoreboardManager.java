package me.seunome.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import me.seunome.claims.ClaimManager;
import me.seunome.economy.EconomyManager;
import me.seunome.geopolitics.NationManager;

public class GeoCraftScoreboardManager {
    private final JavaPlugin plugin;
    private final NationManager nationManager;
    private final EconomyManager economyManager;
    private final ClaimManager claimManager;

    public GeoCraftScoreboardManager(JavaPlugin plugin, NationManager nationManager, EconomyManager economyManager, ClaimManager claimManager) {
        this.plugin = plugin;
        this.nationManager = nationManager;
        this.economyManager = economyManager;
        this.claimManager = claimManager;
        startScoreboardUpdateTask();
    }

    // Método para inicializar a scoreboard para um jogador
    public void setupScoreboard(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard(); // Usa o ScoreboardManager do Bukkit
        Objective objective = scoreboard.registerNewObjective("GeoCraftInfo", "dummy", "§eNovaterra");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Atualiza a scoreboard do jogador
        updateScoreboard(player, scoreboard, objective);
        player.setScoreboard(scoreboard);
    }

    // Método para atualizar a scoreboard de um jogador
    private void updateScoreboard(Player player, Scoreboard scoreboard, Objective objective) {
        // Limpa scores antigos
        for (String entry : scoreboard.getEntries()) {
            scoreboard.resetScores(entry);
        }

        // Adiciona informações
        String playerNation = nationManager.getPlayerNation(player) != null ? nationManager.getPlayerNation(player) : "Nenhuma";
        String currentTerritory = getCurrentTerritory(player) != null ? getCurrentTerritory(player) : "Nenhum";
        double playerBalance = economyManager.getBalance(player);

        // Define as linhas da scoreboard (de baixo pra cima)
        setScore(objective, "§7Dinheiro: §a" + String.format("%.2f", playerBalance) + " dólares", 3);
        setScore(objective, "§7Nação: §e" + playerNation, 2);
        setScore(objective, "§7Território Atual: §6" + currentTerritory, 1);
    }

    // Método auxiliar para definir uma linha na scoreboard
    private void setScore(Objective objective, String text, int score) {
        Score scoreboardScore = objective.getScore(text);
        scoreboardScore.setScore(score);
    }

    // Método para obter o território atual (nação que controla o terreno)
    private String getCurrentTerritory(Player player) {
        Location loc = player.getLocation();
        return claimManager.getClaimNation(loc);
    }

    // Método para iniciar a atualização automática da scoreboard
    private void startScoreboardUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Scoreboard scoreboard = player.getScoreboard();
                    if (scoreboard.getObjective("GeoCraftInfo") == null) {
                        setupScoreboard(player);
                    } else {
                        updateScoreboard(player, scoreboard, scoreboard.getObjective("GeoCraftInfo"));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20L); // Atualiza a cada segundo (20 ticks)
    }
}