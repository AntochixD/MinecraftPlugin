package me.seunome;

import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import me.seunome.claims.ClaimManager;
import me.seunome.economy.BankManager;
import me.seunome.economy.EconomyManager;
import me.seunome.geopolitics.AllianceManager;
import me.seunome.geopolitics.CityManager;
import me.seunome.geopolitics.NationManager;
import me.seunome.geopolitics.WarManager;
import me.seunome.lojas.ShopManager;
import me.seunome.missoes.QuestManager;
import me.seunome.utils.DatabaseManager;
import me.seunome.utils.GeoCraftScoreboardManager;
import me.seunome.utils.MapManager;
import me.seunome.utils.PermissionManager;
import me.seunome.utils.RankingManager;
import net.milkbowl.vault.economy.Economy;

public class MeuPrimeiroPlugin extends JavaPlugin {
    private DatabaseManager databaseManager;
    private EconomyManager economyManager;
    private BankManager bankManager;
    private ClaimManager claimManager;
    private NationManager nationManager;
    private CityManager cityManager;
    private WarManager warManager;
    private AllianceManager allianceManager;
    private ShopManager shopManager;
    private QuestManager questManager;
    private PermissionManager permissionManager;
    private RankingManager rankingManager;
    private MapManager mapManager;
    private GeoCraftScoreboardManager scoreboardManager;
    private Economy vaultEconomy = null;

    @Override
    public void onEnable() {
        // Inicializa o banco de dados e cria as tabelas
        databaseManager = new DatabaseManager(this);
        databaseManager.createTables(); // Cria as tabelas ao iniciar

        // Configura o Vault (economia)
        if (!setupEconomy()) {
            getLogger().severe("§cVault não encontrado! Desativando funcionalidades de ChestShop.");
            shopManager = null; // Desativa ShopManager se Vault não estiver disponível
        } else {
            // Inicializa os gerenciadores
            economyManager = new EconomyManager(this, databaseManager);
            bankManager = new BankManager(this, databaseManager, economyManager);
            claimManager = new ClaimManager(this, databaseManager);
            nationManager = new NationManager(this, databaseManager);
            cityManager = new CityManager(this, databaseManager, nationManager, claimManager);
            warManager = new WarManager(this, databaseManager, nationManager, claimManager);
            allianceManager = new AllianceManager(this, databaseManager, nationManager);
            shopManager = new ShopManager(this, databaseManager, economyManager);
            questManager = new QuestManager(this, databaseManager);
            permissionManager = new PermissionManager(this);
            rankingManager = new RankingManager(this, databaseManager, nationManager, economyManager, claimManager);
            mapManager = new MapManager(this, databaseManager, nationManager, claimManager, warManager);
            scoreboardManager = new GeoCraftScoreboardManager(this, nationManager, economyManager, claimManager);
        }

        // Registra comandos (ignorando shop se Vault não estiver disponível)
        getCommand("money").setExecutor(economyManager);
        getCommand("bank").setExecutor(bankManager);
        getCommand("claim").setExecutor(claimManager);
        getCommand("nation").setExecutor(nationManager);
        getCommand("map").setExecutor(mapManager);
        getCommand("quest").setExecutor(questManager);
        if (shopManager != null) {
            getCommand("shop").setExecutor(shopManager);
        }
        getCommand("ranking").setExecutor(rankingManager);

        getLogger().info("GeoCraft ativado com sucesso!");
    }

    @Override
    public void onDisable() {
        getLogger().info("GeoCraft desativado com sucesso!");
    }

    // Método para configurar o Vault
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        vaultEconomy = rsp.getProvider();
        return vaultEconomy != null;
    }

    // Getters para os gerenciadores
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public EconomyManager getEconomyManager() { return economyManager; }
    public BankManager getBankManager() { return bankManager; }
    public ClaimManager getClaimManager() { return claimManager; }
    public NationManager getNationManager() { return nationManager; }
    public CityManager getCityManager() { return cityManager; }
    public WarManager getWarManager() { return warManager; }
    public AllianceManager getAllianceManager() { return allianceManager; }
    public ShopManager getShopManager() { return shopManager; }
    public QuestManager getQuestManager() { return questManager; }
    public PermissionManager getPermissionManager() { return permissionManager; }
    public RankingManager getRankingManager() { return rankingManager; }
    public MapManager getMapManager() { return mapManager; }
    public GeoCraftScoreboardManager getScoreboardManager() { return scoreboardManager; }
    public Economy getVaultEconomy() { return vaultEconomy; }
}