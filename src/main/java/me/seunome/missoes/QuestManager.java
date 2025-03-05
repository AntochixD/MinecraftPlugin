package me.seunome.missoes;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.economy.EconomyManager;
import me.seunome.utils.DatabaseManager;

public class QuestManager implements Listener, CommandExecutor {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final List<Quest> quests = new ArrayList<>();
    private final Map<UUID, Map<String, Integer>> playerProgress = new HashMap<>();

    public QuestManager(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        loadQuests();
        loadPlayerProgress();
    }

    // Método para carregar missões do config.yml
    private void loadQuests() {
        quests.clear(); // Limpa quests existentes
        if (plugin.getConfig().getConfigurationSection("quests") == null) {
            plugin.getLogger().warning("§eSeção 'quests' não encontrada no config.yml. Usando valores padrão.");
            // Define valores padrão se a seção não existir
            plugin.getConfig().set("quests.daily-reward", 1000.0);
            plugin.getConfig().set("quests.quest-list", Arrays.asList(
                new String[]{
                    "name: Minere 32 Diamantes;description: Minere 32 blocos de diamante para completar esta missão.;reward: 1000.0;type: mining;target: 32;item: DIAMOND_ORE",
                    "name: Mate 10 Zumbis;description: Mate 10 zumbis para completar esta missão.;reward: 1000.0;type: killing;target: 10;entity: ZOMBIE"
                }
            ));
            plugin.saveConfig(); // Salva o config.yml com valores padrão
        }

        ConfigurationSection questSection = plugin.getConfig().getConfigurationSection("quests.quest-list");
        if (questSection != null) {
            for (String key : questSection.getKeys(false)) {
                String questData = plugin.getConfig().getString("quests.quest-list." + key);
                if (questData != null) {
                    Quest quest = parseQuest(questData);
                    if (quest != null) {
                        quests.add(quest);
                    }
                }
            }
        }
    }

    // Método para parsear uma string de missão em um objeto Quest
    private Quest parseQuest(String questData) {
        try {
            String[] parts = questData.split(";");
            String name = null, description = null;
            double reward = 0.0;
            String type = null, item = null, entity = null;
            int target = 0;

            for (String part : parts) {
                String[] keyValue = part.split(":");
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim();
                    String value = keyValue[1].trim();
                    switch (key.toLowerCase()) {
                        case "name": name = value; break;
                        case "description": description = value; break;
                        case "reward": reward = Double.parseDouble(value); break;
                        case "type": type = value; break;
                        case "target": target = Integer.parseInt(value); break;
                        case "item": item = value; break;
                        case "entity": entity = value; break;
                    }
                }
            }

            if (name != null && description != null && type != null && target > 0) {
                return new Quest(name, description, reward, type, target, item, entity);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("§cErro ao parsear missão: " + e.getMessage());
        }
        return null;
    }

    // Método para carregar o progresso dos jogadores do banco de dados
    private void loadPlayerProgress() {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT player_uuid, quest_name, completed, completion_time FROM quests");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                UUID playerUUID = UUID.fromString(rs.getString("player_uuid"));
                String questName = rs.getString("quest_name");
                int completed = rs.getInt("completed");
                long completionTime = rs.getLong("completion_time");

                if (completed == 1) {
                    // Remove a missão completada do progresso (não precisa rastrear)
                    continue;
                }

                playerProgress.computeIfAbsent(playerUUID, k -> new HashMap<>()).put(questName, 0);
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao carregar progresso de missões: " + e.getMessage());
        }
    }

    // Método para listar missões disponíveis (usado por /quest list)
    public void listQuests(Player player) {
        if (!(player instanceof Player)) {
            player.sendMessage("§cEste comando só pode ser usado por jogadores!");
            return;
        }
        player.sendMessage("§eMissões Disponíveis:");
        for (Quest quest : quests) {
            String progress = getQuestProgress(player, quest);
            player.sendMessage("§6- " + quest.getName() + " (§7" + quest.getDescription() + "§6) - Progresso: " + progress + " - Recompensa: §a" + quest.getReward() + " dólares");
        }
    }

    // Método para obter o progresso de uma missão
    private String getQuestProgress(Player player, Quest quest) {
        UUID playerUUID = player.getUniqueId();
        Map<String, Integer> progress = playerProgress.getOrDefault(playerUUID, new HashMap<>());
        int current = progress.getOrDefault(quest.getName(), 0);
        return current + "/" + quest.getTarget();
    }

    // Método para atualizar o progresso de uma missão (mineração)
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String blockType = event.getBlock().getType().toString();
        for (Quest quest : quests) {
            if (quest.getType().equals("mining") && quest.getItem() != null && quest.getItem().equals(blockType)) {
                updateQuestProgress(player, quest, 1);
            }
        }
    }

    // Método para atualizar o progresso de uma missão (matar entidades)
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() instanceof Player) {
            Player player = event.getEntity().getKiller();
            String entityType = event.getEntityType().toString();
            for (Quest quest : quests) {
                if (quest.getType().equals("killing") && quest.getEntity() != null && quest.getEntity().equals(entityType)) {
                    updateQuestProgress(player, quest, 1);
                }
            }
        }
    }

    // Método para atualizar o progresso de uma missão
    private void updateQuestProgress(Player player, Quest quest, int amount) {
        UUID playerUUID = player.getUniqueId();
        playerProgress.computeIfAbsent(playerUUID, k -> new HashMap<>()).merge(quest.getName(), amount, Integer::sum);
        int current = playerProgress.get(playerUUID).get(quest.getName());
        if (current >= quest.getTarget()) {
            completeQuest(player, quest);
        } else {
            player.sendMessage("§eProgresso atualizado: " + quest.getName() + " - " + getQuestProgress(player, quest));
        }
    }

    // Método para completar uma missão (usado por /quest complete)
    public void completeQuest(Player player, Quest quest) {
        UUID playerUUID = player.getUniqueId();
        if (!playerProgress.containsKey(playerUUID) || !playerProgress.get(playerUUID).containsKey(quest.getName())) {
            player.sendMessage("§cVocê não tem esta missão ativa!");
            return;
        }

        // Verifica se a missão já foi completada hoje
        try {
            PreparedStatement checkStmt = databaseManager.getConnection().prepareStatement(
                    "SELECT completion_time FROM quests WHERE player_uuid = ? AND quest_name = ?");
            checkStmt.setString(1, playerUUID.toString());
            checkStmt.setString(2, quest.getName());
            ResultSet rs = checkStmt.executeQuery();
            long lastCompletion = rs.next() ? rs.getLong("completion_time") : 0;
            long now = System.currentTimeMillis();
            if (lastCompletion > 0 && (now - lastCompletion) < 24 * 60 * 60 * 1000) { // 24 horas
                player.sendMessage("§cVocê já completou esta missão hoje!");
                return;
            }
            rs.close();
            checkStmt.close();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao verificar missão completada: " + e.getMessage());
            return;
        }

        // Adiciona a recompensa (verifica se EconomyManager existe)
        EconomyManager economyManager = ((MeuPrimeiroPlugin) plugin).getEconomyManager();
        if (economyManager != null) {
            economyManager.addBalance(player, quest.getReward());
            player.sendMessage("§aMissão completada: " + quest.getName() + "! Você ganhou §a" + quest.getReward() + " dólares.");
        } else {
            player.sendMessage("§cErro: Sistema de economia não disponível. Contate um administrador!");
            plugin.getLogger().severe("§cEconomyManager não inicializado no QuestManager!");
        }

        // Salva no banco de dados
        try {
            PreparedStatement updateStmt = databaseManager.getConnection().prepareStatement(
                    "INSERT OR REPLACE INTO quests (player_uuid, quest_name, completed, completion_time) VALUES (?, ?, 1, ?)");
            updateStmt.setString(1, playerUUID.toString());
            updateStmt.setString(2, quest.getName());
            updateStmt.setLong(3, System.currentTimeMillis());
            updateStmt.executeUpdate();
            updateStmt.close();

            // Remove o progresso da missão completada
            playerProgress.get(playerUUID).remove(quest.getName());
            if (playerProgress.get(playerUUID).isEmpty()) {
                playerProgress.remove(playerUUID);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao salvar missão completada: " + e.getMessage());
        }
    }

    // Método para lidar com comandos /quest
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando só pode ser usado por jogadores!");
            return true;
        }

        Player player = (Player) sender;
        if (command.getName().equalsIgnoreCase("quest")) {
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                listQuests(player);
                return true;
            } else if (args[0].equalsIgnoreCase("complete")) {
                if (args.length < 2) {
                    player.sendMessage("§cUso: /quest complete <nome_da_missão>");
                    return true;
                }
                String questName = args[1];
                Quest questToComplete = null;
                for (Quest quest : quests) {
                    if (quest.getName().equalsIgnoreCase(questName)) {
                        questToComplete = quest;
                        break;
                    }
                }
                if (questToComplete == null) {
                    player.sendMessage("§cMissão não encontrada: " + questName);
                    return true;
                }
                completeQuest(player, questToComplete);
                return true;
            }
            player.sendMessage("§cUso: /quest [list|complete <nome_da_missão>]");
            return true;
        }
        return false;
    }

    // Classe interna para representar uma missão
    public static class Quest {
        private final String name;
        private final String description;
        private final double reward;
        private final String type;
        private final int target;
        private final String item;
        private final String entity;

        public Quest(String name, String description, double reward, String type, int target, String item, String entity) {
            this.name = name;
            this.description = description;
            this.reward = reward;
            this.type = type;
            this.target = target;
            this.item = item;
            this.entity = entity;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public double getReward() { return reward; }
        public String getType() { return type; }
        public int getTarget() { return target; }
        public String getItem() { return item; }
        public String getEntity() { return entity; }
    }
}