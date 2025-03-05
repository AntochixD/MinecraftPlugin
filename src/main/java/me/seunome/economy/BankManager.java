package me.seunome.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.bukkit.Bukkit; // Adicionado para resolver "Bukkit cannot be resolved"
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.geopolitics.NationManager;
import me.seunome.utils.DatabaseManager;

public class BankManager implements CommandExecutor {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final EconomyManager economyManager;
    private final NationManager nationManager;

    // Construtor para receber a instância do plugin e gerenciadores
    public BankManager(JavaPlugin plugin, DatabaseManager databaseManager, EconomyManager economyManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.economyManager = economyManager;
        this.nationManager = ((MeuPrimeiroPlugin) plugin).getNationManager();
        startDailyTax(); // Inicia imposto diário
    }
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Aqui você coloca a lógica do comando /bank
        sender.sendMessage("Você usou o comando /bank!");
        return true;
    }

    // Depositar dinheiro no banco pessoal
    public void deposit(Player player, double amount) {
        if (economyManager.getBalance(player) >= amount) {
            economyManager.removeBalance(player, amount);
            addToPlayerBank(player, amount);

            // Aplica taxa de transação (2%, configurável)
            double tax = amount * (plugin.getConfig().getDouble("bank-tax-per-transaction", 2.0) / 100.0);
            String nation = nationManager.getPlayerNation(player);
            if (nation != null) {
                addToNationTreasury(nation, tax);
                player.sendMessage("§eUma taxa de " + tax + " dólares foi aplicada ao tesouro da sua nação!");
            }

            player.sendMessage("§aVocê depositou " + amount + " dólares no banco!");
        } else {
            player.sendMessage("§cVocê não tem dólares suficientes!");
        }
    }

    // Sacar dinheiro do banco pessoal
    public void withdraw(Player player, double amount) {
        double bankBalance = getPlayerBankBalance(player);
        if (bankBalance >= amount) {
            removeFromPlayerBank(player, amount);
            economyManager.addBalance(player, amount);

            // Aplica taxa de transação (2%, configurável)
            double tax = amount * (plugin.getConfig().getDouble("bank-tax-per-transaction", 2.0) / 100.0);
            String nation = nationManager.getPlayerNation(player);
            if (nation != null) {
                addToNationTreasury(nation, tax);
                player.sendMessage("§eUma taxa de " + tax + " dólares foi aplicada ao tesouro da sua nação!");
            }

            player.sendMessage("§aVocê sacou " + amount + " dólares do banco!");
        } else {
            player.sendMessage("§cVocê não tem dólares suficientes no banco!");
        }
    }

    // Obtém o saldo do banco pessoal
    private double getPlayerBankBalance(Player player) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT bank_balance FROM player_banks WHERE player_uuid = ?");
            stmt.setString(1, player.getUniqueId().toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getDouble("bank_balance");
            } else {
                return 0.0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao obter saldo do banco: " + e.getMessage());
            return 0.0;
        }
    }

    // Adiciona ao saldo do banco pessoal
    private void addToPlayerBank(Player player, double amount) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT OR REPLACE INTO player_banks (player_uuid, bank_balance) VALUES (?, ?)");
            stmt.setString(1, player.getUniqueId().toString());
            stmt.setDouble(2, getPlayerBankBalance(player) + amount);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao adicionar ao banco: " + e.getMessage());
        }
    }

    // Remove do saldo do banco pessoal
    private void removeFromPlayerBank(Player player, double amount) {
        double currentBalance = getPlayerBankBalance(player);
        if (currentBalance >= amount) {
            try {
                PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                        "INSERT OR REPLACE INTO player_banks (player_uuid, bank_balance) VALUES (?, ?)");
                stmt.setString(1, player.getUniqueId().toString());
                stmt.setDouble(2, currentBalance - amount);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("§cErro ao remover do banco: " + e.getMessage());
            }
        }
    }

    // Depositar no tesouro nacional (só líder)
    public void depositToNationTreasury(Player player, double amount) {
        if (!nationManager.isNationLeader(player)) {
            player.sendMessage("§cApenas o líder da nação pode modificar o tesouro!");
            return;
        }

        String nation = nationManager.getPlayerNation(player);
        if (nation == null) {
            player.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        if (economyManager.getBalance(player) >= amount) {
            economyManager.removeBalance(player, amount);
            addToNationTreasury(nation, amount);
            player.sendMessage("§aVocê depositou " + amount + " dólares no tesouro da nação " + nation + "!");
        } else {
            player.sendMessage("§cVocê não tem dólares suficientes!");
        }
    }

    // Sacar do tesouro nacional (só líder)
    public void withdrawFromNationTreasury(Player player, double amount) {
        if (!nationManager.isNationLeader(player)) {
            player.sendMessage("§cApenas o líder da nação pode modificar o tesouro!");
            return;
        }

        String nation = nationManager.getPlayerNation(player);
        if (nation == null) {
            player.sendMessage("§cVocê não pertence a uma nação!");
            return;
        }

        double treasury = getNationTreasury(nation);
        if (treasury >= amount) {
            removeFromNationTreasury(nation, amount);
            economyManager.addBalance(player, amount);
            player.sendMessage("§aVocê sacou " + amount + " dólares do tesouro da nação " + nation + "!");
        } else {
            player.sendMessage("§cO tesouro da nação não tem dólares suficientes!");
        }
    }

    // Obtém o tesouro de uma nação
    public double getNationTreasury(String nation) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT treasury FROM nations WHERE name = ?");
            stmt.setString(1, nation);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getDouble("treasury");
            }
            return 0.0;
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao obter tesouro: " + e.getMessage());
            return 0.0;
        }
    }

    // Adiciona ao tesouro de uma nação
    public void addToNationTreasury(String nation, double amount) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT OR REPLACE INTO nations (name, treasury) VALUES (?, ?) " +
                    "ON CONFLICT(name) DO UPDATE SET treasury = treasury + ?");
            stmt.setString(1, nation);
            stmt.setDouble(2, getNationTreasury(nation) + amount);
            stmt.setDouble(3, amount);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao adicionar ao tesouro: " + e.getMessage());
        }
    }

    // Remove do tesouro de uma nação
    public void removeFromNationTreasury(String nation, double amount) {
        double currentTreasury = getNationTreasury(nation);
        if (currentTreasury >= amount) {
            try {
                PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                        "INSERT OR REPLACE INTO nations (name, treasury) VALUES (?, ?) " +
                        "ON CONFLICT(name) DO UPDATE SET treasury = treasury - ?");
                stmt.setString(1, nation);
                stmt.setDouble(2, currentTreasury - amount);
                stmt.setDouble(3, amount);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("§cErro ao remover do tesouro: " + e.getMessage());
            }
        }
    }

    // Inicia o imposto diário de 1% no banco pessoal
    private void startDailyTax() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    double bankBalance = getPlayerBankBalance(player);
                    if (bankBalance > 0) {
                        double tax = bankBalance * (plugin.getConfig().getDouble("bank-tax-per-day", 1.0) / 100.0);
                        removeFromPlayerBank(player, tax);
                        String nation = nationManager.getPlayerNation(player);
                        if (nation != null) {
                            addToNationTreasury(nation, tax);
                            player.sendMessage("§eUm imposto diário de " + tax + " dólares foi cobrado do seu banco e adicionado ao tesouro da nação!");
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20 * 60 * 60 * 24L); // 24 horas (86.400 segundos)
    }
}