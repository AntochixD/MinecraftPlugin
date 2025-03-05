package me.seunome.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.utils.DatabaseManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

public class EconomyManager implements Listener, org.bukkit.command.CommandExecutor {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final BankManager bankManager;
    private final Map<UUID, Long> playerOnlineTimes = new HashMap<>(); // Rastreia tempo online por jogador
    private Economy vaultEconomy; // Para verificar se já existe um provedor

    public EconomyManager(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.bankManager = new BankManager(plugin, databaseManager, this); // Inicializa BankManager
        plugin.getServer().getPluginManager().registerEvents(this, plugin); // Registra este listener

        // Verifica se o Vault está presente e registra nossa economia
        setupVault();

        // Inicia o timer de recompensas online
        startOnlineRewards();
    }

    private void setupVault() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("§eVault não encontrado! Funcionalidades do ChestShop podem não funcionar.");
            return;
        }

        // Verifica se já existe um provedor de economia registrado
        vaultEconomy = Bukkit.getServicesManager().getRegistration(Economy.class) != null ? 
                       Bukkit.getServicesManager().getRegistration(Economy.class).getProvider() : null;

        if (vaultEconomy == null) {
            // Registra nossa economia personalizada
            GeoCraftEconomy economyProvider = new GeoCraftEconomy(this);
            Bukkit.getServicesManager().register(Economy.class, economyProvider, plugin, ServicePriority.Normal);
            plugin.getLogger().info("§aEconomia personalizada GeoCraft registrada no Vault com sucesso!");
            vaultEconomy = economyProvider;
        } else {
            plugin.getLogger().warning("§eUm provedor de economia já está registrado no Vault. Usando-o como fallback.");
        }
    }

    // Adiciona dólares ao saldo de um jogador ou OfflinePlayer
    public void addBalance(Player player, double amount) {
        double currentBalance = getBalance(player);
        setBalance(player, currentBalance + amount);
    }

    public void addBalance(OfflinePlayer offlinePlayer, double amount) {
        if (offlinePlayer instanceof Player) {
            addBalance((Player) offlinePlayer, amount);
        } else {
            try {
                PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                        "INSERT OR REPLACE INTO player_balances (player_uuid, balance) VALUES (?, ?)");
                stmt.setString(1, offlinePlayer.getUniqueId().toString());
                stmt.setDouble(2, getBalance(offlinePlayer) + amount);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("§cErro ao adicionar saldo para OfflinePlayer: " + e.getMessage());
            }
        }
    }

    // Remove dólares do saldo de um jogador ou OfflinePlayer
    public void removeBalance(Player player, double amount) {
        double currentBalance = getBalance(player);
        if (currentBalance >= amount) {
            setBalance(player, currentBalance - amount);
        } else {
            player.sendMessage("§cVocê não tem dólares suficientes!");
        }
    }

    public void removeBalance(OfflinePlayer offlinePlayer, double amount) {
        if (offlinePlayer instanceof Player) {
            removeBalance((Player) offlinePlayer, amount);
        } else {
            try {
                double currentBalance = getBalance(offlinePlayer);
                if (currentBalance >= amount) {
                    PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                            "INSERT OR REPLACE INTO player_balances (player_uuid, balance) VALUES (?, ?)");
                    stmt.setString(1, offlinePlayer.getUniqueId().toString());
                    stmt.setDouble(2, currentBalance - amount);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("§cErro ao remover saldo para OfflinePlayer: " + e.getMessage());
            }
        }
    }

    // Obtém o saldo de um jogador ou OfflinePlayer
    public double getBalance(Player player) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT balance FROM player_balances WHERE player_uuid = ?");
            stmt.setString(1, player.getUniqueId().toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getDouble("balance");
            } else {
                double startBalance = plugin.getConfig().getDouble("economy-start-balance", 100.0);
                setBalance(player, startBalance);
                return startBalance;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao obter saldo: " + e.getMessage());
            return 0.0;
        }
    }

    public double getBalance(OfflinePlayer offlinePlayer) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT balance FROM player_balances WHERE player_uuid = ?");
            stmt.setString(1, offlinePlayer.getUniqueId().toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getDouble("balance");
            } else {
                return 0.0; // OfflinePlayer sem saldo retorna 0
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao obter saldo para OfflinePlayer: " + e.getMessage());
            return 0.0;
        }
    }

    // Define o saldo de um jogador
    private void setBalance(Player player, double amount) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT OR REPLACE INTO player_balances (player_uuid, balance) VALUES (?, ?)");
            stmt.setString(1, player.getUniqueId().toString());
            stmt.setDouble(2, amount);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao definir saldo: " + e.getMessage());
        }
    }

    // Transfere dinheiro entre jogadores
    public void transferMoney(Player sender, Player target, double amount) {
        if (getBalance(sender) >= amount) {
            removeBalance(sender, amount);
            addBalance(target, amount);

            String senderNation = ((MeuPrimeiroPlugin) plugin).getNationManager().getPlayerNation(sender);
            if (senderNation != null) {
                double tax = amount * (plugin.getConfig().getDouble("bank-tax-per-transaction", 2.0) / 100.0);
                bankManager.addToNationTreasury(senderNation, tax);
                sender.sendMessage("§eUma taxa de " + tax + " dólares foi aplicada ao tesouro da sua nação!");
            }

            addTransaction(sender, target, amount, "transfer");
            sender.sendMessage("§aVocê transferiu " + amount + " dólares para " + target.getName() + "!");
            target.sendMessage("§aVocê recebeu " + amount + " dólares de " + sender.getName() + "!");
        } else {
            sender.sendMessage("§cVocê não tem dólares suficientes!");
        }
    }

    // Recompensa por matar mobs
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() instanceof Player) {
            Player killer = event.getEntity().getKiller();
            EntityType mobType = event.getEntityType();
            double reward = getMobReward(mobType);
            if (reward > 0) {
                addBalance(killer, reward);
                killer.sendMessage("§aVocê ganhou " + reward + " dólares por matar um " + mobType.name().toLowerCase() + "!");
                addTransaction(killer, null, reward, "reward_mob");
            }
        }
    }

    private double getMobReward(EntityType mobType) {
        String path = "mob-kill-reward." + mobType.name().toLowerCase();
        if (plugin.getConfig().contains(path)) {
            return plugin.getConfig().getDouble(path, 1.0);
        }
        return plugin.getConfig().getDouble("mob-kill-reward.default", 1.0);
    }

    // Recompensas online
    private void startOnlineRewards() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    double reward = plugin.getConfig().getDouble("online-reward-amount", 30.0);
                    addBalance(player, reward);
                    player.sendMessage("§aVocê ganhou " + reward + " dólares por estar online!");
                    addTransaction(player, null, reward, "reward_online");
                }
            }
        }.runTaskTimer(plugin, 0L, 20 * 60 * 5L); // 5 minutos
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        playerOnlineTimes.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerOnlineTimes.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        double currentBalance = getBalance(player);
        if (currentBalance > 0) {
            double loss = currentBalance * 0.20;
            removeBalance(player, loss);
            player.sendMessage("§cVocê perdeu " + loss + " dólares ao morrer!");
            addTransaction(player, null, loss, "loss_death");
        }
    }

    // Getter para Vault (usado por ShopManager)
    public Economy getVaultEconomy() {
        return vaultEconomy;
    }

    // Métodos para top balances e histórico
    public Map<UUID, Double> getTopBalances(int limit) {
        Map<UUID, Double> topBalances = new HashMap<>();
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT player_uuid, balance FROM player_balances ORDER BY balance DESC LIMIT ?");
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                UUID playerUUID = UUID.fromString(rs.getString("player_uuid"));
                double balance = rs.getDouble("balance");
                topBalances.put(playerUUID, balance);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao listar top balances: " + e.getMessage());
        }
        return topBalances;
    }

    public void addTransaction(Player sender, Player target, double amount, String type) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO transaction_history (sender_uuid, target_uuid, amount, type, timestamp) VALUES (?, ?, ?, ?, ?)");
            stmt.setString(1, sender.getUniqueId().toString());
            stmt.setString(2, target != null ? target.getUniqueId().toString() : null);
            stmt.setDouble(3, amount);
            stmt.setString(4, type);
            stmt.setLong(5, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao registrar transação: " + e.getMessage());
        }
    }

    public Map<String, Object> getRecentTransactions(Player player, int limit) {
        Map<String, Object> transactions = new HashMap<>();
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT sender_uuid, target_uuid, amount, type, timestamp FROM transaction_history " +
                    "WHERE sender_uuid = ? OR target_uuid = ? ORDER BY timestamp DESC LIMIT ?");
            stmt.setString(1, player.getUniqueId().toString());
            stmt.setString(2, player.getUniqueId().toString());
            stmt.setInt(3, limit);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String senderUUID = rs.getString("sender_uuid");
                String targetUUID = rs.getString("target_uuid");
                double amount = rs.getDouble("amount");
                String type = rs.getString("type");
                long timestamp = rs.getLong("timestamp");
                transactions.put("sender_" + timestamp, Bukkit.getOfflinePlayer(UUID.fromString(senderUUID)).getName());
                transactions.put("target_" + timestamp, targetUUID != null ? Bukkit.getOfflinePlayer(UUID.fromString(targetUUID)).getName() : "N/A");
                transactions.put("amount_" + timestamp, amount);
                transactions.put("type_" + timestamp, type);
                transactions.put("timestamp_" + timestamp, new Date(timestamp));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao obter histórico de transações: " + e.getMessage());
        }
        return transactions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando só pode ser usado por jogadores!");
            return true;
        }

        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("money")) {
            if (args.length == 0 || args[0].equalsIgnoreCase("balance")) {
                double balance = getBalance(player);
                player.sendMessage("§aSeu saldo: " + balance + " dólar" + (balance != 1 ? "es" : "") + "!");
                return true;
            } else if (args[0].equalsIgnoreCase("give") && args.length == 3) {
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage("§cJogador não encontrado!");
                    return true;
                }
                try {
                    double amount = Double.parseDouble(args[2]);
                    if (amount <= 0) {
                        player.sendMessage("§cA quantia deve ser positiva!");
                        return true;
                    }
                    transferMoney(player, target, amount);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cQuantia inválida!");
                }
                return true;
            } else if (args[0].equalsIgnoreCase("top") && args.length == 1) {
                Map<UUID, Double> topBalances = getTopBalances(10);
                player.sendMessage("§eTop 10 Jogadores Mais Ricos:");
                int rank = 1;
                for (Map.Entry<UUID, Double> entry : topBalances.entrySet()) {
                    Player topPlayer = Bukkit.getPlayer(entry.getKey());
                    String name = topPlayer != null ? topPlayer.getName() : Bukkit.getOfflinePlayer(entry.getKey()).getName();
                    player.sendMessage("§e" + rank + ". " + name + " - " + entry.getValue() + " dólares");
                    rank++;
                }
                return true;
            } else if (args[0].equalsIgnoreCase("history") && args.length == 1) {
                Map<String, Object> transactions = getRecentTransactions(player, 10);
                if (transactions.isEmpty()) {
                    player.sendMessage("§cNenhuma transação recente encontrada!");
                    return true;
                }
                player.sendMessage("§eHistórico de Transações Recentes:");
                for (int i = 0; i < transactions.size(); i += 5) {
                    long timestamp = ((Date) transactions.get("timestamp_" + transactions.get("timestamp_" + i))).getTime();
                    String senderName = (String) transactions.get("sender_" + timestamp);
                    String targetName = (String) transactions.get("target_" + timestamp);
                    double amount = (Double) transactions.get("amount_" + timestamp);
                    String type = (String) transactions.get("type_" + timestamp);
                    player.sendMessage("§e- " + senderName + " → " + targetName + ": " + amount + " dólares (" + type + ") em " + new Date(timestamp));
                }
                return true;
            }
        }
        return false;
    }

    // Classe interna para implementar a interface Economy do Vault
    private static class GeoCraftEconomy implements Economy {
        private final EconomyManager economyManager;

        public GeoCraftEconomy(EconomyManager economyManager) {
            this.economyManager = economyManager;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "GeoCraftEconomy";
        }

        @Override
        public boolean hasBankSupport() {
            return false; // Não suporta bancos do Vault, usamos nosso BankManager
        }

        @Override
        public int fractionalDigits() {
            return 2; // 2 casas decimais
        }

        @Override
        public String format(double amount) {
            return String.format("%.2f dólares", amount);
        }

        @Override
        public String currencyNamePlural() {
            return "dólares";
        }

        @Override
        public String currencyNameSingular() {
            return "dólar";
        }

        @Override
        public boolean hasAccount(OfflinePlayer player) {
            return economyManager.getBalance(player) >= 0; // Sempre true, pois criamos saldo inicial se não existir
        }

        @Override
        public boolean hasAccount(OfflinePlayer player, String worldName) {
            return hasAccount(player); // Ignoramos mundos
        }

        @Override
        public boolean hasAccount(String playerName) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return hasAccount(player);
        }

        @Override
        public boolean hasAccount(String playerName, String worldName) {
            return hasAccount(playerName); // Ignoramos mundos
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            return economyManager.getBalance(player);
        }

        @Override
        public double getBalance(OfflinePlayer player, String worldName) {
            return getBalance(player); // Ignoramos mundos
        }

        @Override
        public double getBalance(String playerName) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return getBalance(player);
        }

        @Override
        public double getBalance(String playerName, String worldName) {
            return getBalance(playerName); // Ignoramos mundos
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return getBalance(player) >= amount;
        }

        @Override
        public boolean has(OfflinePlayer player, String worldName, double amount) {
            return has(player, amount); // Ignoramos mundos
        }

        @Override
        public boolean has(String playerName, double amount) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return has(player, amount);
        }

        @Override
        public boolean has(String playerName, String worldName, double amount) {
            return has(playerName, amount); // Ignoramos mundos
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            double balance = economyManager.getBalance(player);
            if (balance >= amount) {
                economyManager.removeBalance(player, amount);
                return new EconomyResponse(amount, balance - amount, EconomyResponse.ResponseType.SUCCESS, null);
            } else {
                return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, "Saldo insuficiente");
            }
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
            return withdrawPlayer(player, amount); // Ignoramos mundos
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, double amount) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return withdrawPlayer(player, amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
            return withdrawPlayer(playerName, amount); // Ignoramos mundos
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            economyManager.addBalance(player, amount);
            return new EconomyResponse(amount, economyManager.getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
            return depositPlayer(player, amount); // Ignoramos mundos
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, double amount) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return depositPlayer(player, amount);
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            return depositPlayer(playerName, amount); // Ignoramos mundos
        }

        @Override
        public EconomyResponse createBank(String name, OfflinePlayer player) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bancos não suportados");
        }

        @Override
        public EconomyResponse createBank(String name, String playerName) {
            return createBank(name, Bukkit.getOfflinePlayer(playerName));
        }

        @Override
        public EconomyResponse deleteBank(String name) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bancos não suportados");
        }

        @Override
        public EconomyResponse bankBalance(String name) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bancos não suportados");
        }

        @Override
        public EconomyResponse bankHas(String name, double amount) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bancos não suportados");
        }

        @Override
        public EconomyResponse bankWithdraw(String name, double amount) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bancos não suportados");
        }

        @Override
        public EconomyResponse bankDeposit(String name, double amount) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bancos não suportados");
        }

        @Override
        public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bancos não suportados");
        }

        @Override
        public EconomyResponse isBankOwner(String name, String playerName) {
            return isBankOwner(name, Bukkit.getOfflinePlayer(playerName));
        }

        @Override
        public EconomyResponse isBankMember(String name, OfflinePlayer player) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bancos não suportados");
        }

        @Override
        public EconomyResponse isBankMember(String name, String playerName) {
            return isBankMember(name, Bukkit.getOfflinePlayer(playerName));
        }

        @Override
        public java.util.List<String> getBanks() {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player) {
            return true; // Conta é criada automaticamente ao obter saldo
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
            return createPlayerAccount(player); // Ignoramos mundos
        }

        @Override
        public boolean createPlayerAccount(String playerName) {
            return createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
        }

        @Override
        public boolean createPlayerAccount(String playerName, String worldName) {
            return createPlayerAccount(playerName); // Ignoramos mundos
        }
    }
}