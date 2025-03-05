package me.seunome.utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.bukkit.plugin.java.JavaPlugin;

public class DatabaseManager {
    private final JavaPlugin plugin;
    private Connection connection;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.connection = null;
        connect();
    }

    public void connect() {
        try {
            if (connection == null || connection.isClosed()) {
                String url = "jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/geocraft.db";
                connection = DriverManager.getConnection(url);
                plugin.getLogger().info("§aConectado ao banco de dados SQLite com sucesso!");
                createTables(); // Cria as tabelas ao conectar
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao conectar ao banco de dados SQLite: " + e.getMessage());
        }
    }

    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("§aDesconectado do banco de dados SQLite com sucesso!");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao desconectar do banco de dados SQLite: " + e.getMessage());
        }
    }

    public Connection getConnection() {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        return connection;
    }

    // Método para criar todas as tabelas necessárias
    public void createTables() {
        try {
            Connection conn = getConnection();
            Statement stmt = conn.createStatement();

            // Tabela para nações
            stmt.execute("CREATE TABLE IF NOT EXISTS nations (name TEXT PRIMARY KEY, leader TEXT, treasury REAL)");

            // Tabela para membros de nações
            stmt.execute("CREATE TABLE IF NOT EXISTS nation_members (nation TEXT, player_uuid TEXT, FOREIGN KEY (nation) REFERENCES nations(name))");

            // Tabela para claims
            stmt.execute("CREATE TABLE IF NOT EXISTS claims (nation TEXT, world TEXT, min_x INTEGER, max_x INTEGER, min_z INTEGER, max_z INTEGER, FOREIGN KEY (nation) REFERENCES nations(name))");

            // Tabela para cidades
            stmt.execute("CREATE TABLE IF NOT EXISTS cities (nation TEXT, name TEXT, world TEXT, min_x INTEGER, max_x INTEGER, min_z INTEGER, max_z INTEGER, FOREIGN KEY (nation) REFERENCES nations(name))");

            // Tabela para alianças
            stmt.execute("CREATE TABLE IF NOT EXISTS alliances (name TEXT PRIMARY KEY, leader_nation TEXT, FOREIGN KEY (leader_nation) REFERENCES nations(name))");

            // Tabela para membros de alianças
            stmt.execute("CREATE TABLE IF NOT EXISTS alliance_members (alliance TEXT, nation TEXT, join_time INTEGER, FOREIGN KEY (alliance) REFERENCES alliances(name), FOREIGN KEY (nation) REFERENCES nations(name))");

            // Tabela para tréguas
            stmt.execute("CREATE TABLE IF NOT EXISTS nation_truces (nation1 TEXT, nation2 TEXT, expiration INTEGER, accepted INTEGER DEFAULT 0, FOREIGN KEY (nation1) REFERENCES nations(name), FOREIGN KEY (nation2) REFERENCES nations(name))");

            // Tabela para guerras
            stmt.execute("CREATE TABLE IF NOT EXISTS wars (attacker TEXT, defender TEXT, start_time INTEGER, FOREIGN KEY (attacker) REFERENCES nations(name), FOREIGN KEY (defender) REFERENCES nations(name))");

            // Tabela para economia (dinheiro dos jogadores)
            stmt.execute("CREATE TABLE IF NOT EXISTS player_balances (player_uuid TEXT PRIMARY KEY, balance REAL)");

            // Tabela para lojas (ChestShop)
            stmt.execute("CREATE TABLE IF NOT EXISTS shops (owner TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, item TEXT, price REAL, FOREIGN KEY (owner) REFERENCES nations(name) OR player_balances(player_uuid))");

            // Tabela para missões diárias
            stmt.execute("CREATE TABLE IF NOT EXISTS quests (player_uuid TEXT, quest_name TEXT, completed INTEGER, completion_time INTEGER, FOREIGN KEY (player_uuid) REFERENCES player_balances(player_uuid))");

            stmt.close();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao criar tabelas no banco de dados: " + e.getMessage());
        }
    }
}