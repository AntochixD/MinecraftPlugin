package me.seunome.lojas;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.claims.ClaimManager;
import me.seunome.economy.EconomyManager;
import me.seunome.geopolitics.NationManager;
import me.seunome.utils.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent; // Adicionado para resolver o erro
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.UUID;

public class ShopManager implements Listener, CommandExecutor {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final NationManager nationManager;
    private final EconomyManager economyManager;
    private final ClaimManager claimManager;
    private final HashMap<Location, Shop> shops = new HashMap<>(); // Armazena lojas por localização

    // Classe interna para representar uma loja
    private static class Shop {
        private final UUID owner;
        private final ItemStack item;
        private final double buyPrice;
        private final double sellPrice;

        public Shop(UUID owner, ItemStack item, double buyPrice, double sellPrice) {
            this.owner = owner;
            this.item = item;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
        }

        public UUID getOwner() { return owner; }
        public ItemStack getItem() { return item; }
        public double getBuyPrice() { return buyPrice; }
        public double getSellPrice() { return sellPrice; }
    }

    // Construtor para receber a instância do plugin e gerenciadores
    public ShopManager(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.nationManager = ((MeuPrimeiroPlugin) plugin).getNationManager();
        this.economyManager = ((MeuPrimeiroPlugin) plugin).getEconomyManager();
        this.claimManager = ((MeuPrimeiroPlugin) plugin).getClaimManager();
        plugin.getServer().getPluginManager().registerEvents(this, plugin); // Registra este listener

        // Carrega lojas do banco de dados ao iniciar
        loadShops();
    }

    // Método para criar uma loja com ChestShop
    public void createShop(Player player, Location loc, ItemStack item, double buyPrice, double sellPrice) {
        if (!isInClaimOfNation(player, loc)) {
            player.sendMessage("§cVocê só pode criar lojas em terrenos da sua nação!");
            return;
        }

        Chest chest = (Chest) loc.getBlock().getState();
        UUID owner = player.getUniqueId();

        // Salva a loja no banco de dados e memória
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO shops (owner, world, x, y, z, item_type, buy_price, sell_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            stmt.setString(1, owner.toString());
            stmt.setString(2, loc.getWorld().getName());
            stmt.setInt(3, loc.getBlockX());
            stmt.setInt(4, loc.getBlockY());
            stmt.setInt(5, loc.getBlockZ());
            stmt.setString(6, item.getType().name());
            stmt.setDouble(7, buyPrice);
            stmt.setDouble(8, sellPrice);
            stmt.executeUpdate();

            shops.put(loc, new Shop(owner, item, buyPrice, sellPrice));

            player.sendMessage("§aLoja criada com sucesso! Compra: " + buyPrice + "$, Venda: " + sellPrice + "$ por " + item.getType().name() + ".");
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao criar loja: " + e.getMessage());
            player.sendMessage("§cErro ao criar loja. Contate um administrador!");
        }
    }

    // Método para verificar se a localização está em um claim da nação do jogador
    private boolean isInClaimOfNation(Player player, Location loc) {
        String playerNation = nationManager.getPlayerNation(player);
        if (playerNation == null) return false;
        if (!claimManager.isInClaim(loc)) return false;
        String claimNation = claimManager.getClaimNation(loc);
        return claimNation != null && claimNation.equals(playerNation);
    }

    // Método para carregar lojas do banco de dados
    private void loadShops() {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "SELECT owner, world, x, y, z, item_type, buy_price, sell_price FROM shops");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                UUID owner = UUID.fromString(rs.getString("owner"));
                String world = rs.getString("world");
                int x = rs.getInt("x");
                int y = rs.getInt("y");
                int z = rs.getInt("z");
                Material itemType = Material.valueOf(rs.getString("item_type"));
                double buyPrice = rs.getDouble("buy_price");
                double sellPrice = rs.getDouble("sell_price");

                Location loc = new Location(Bukkit.getWorld(world), x, y, z);
                shops.put(loc, new Shop(owner, new ItemStack(itemType), buyPrice, sellPrice));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao carregar lojas: " + e.getMessage());
        }
    }

    // Evento para criar uma loja ao colocar um baú com sinal
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (event.getBlock().getType() != Material.CHEST) return;

        Location loc = event.getBlock().getLocation();
        if (!player.getInventory().getItemInMainHand().getType().equals(Material.OAK_SIGN)) return; // Corrigido de SIGN para OAK_SIGN

        // Simula ChestShop: o jogador usa um sinal para configurar a loja
        player.sendMessage("§eClique no baú com o sinal para configurar a loja (item, preço de compra, preço de venda).");
        shops.put(loc, null); // Marca como loja em configuração
    }

    // Evento para configurar uma loja ao interagir com o baú
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock().getType() != Material.CHEST) return;

        Player player = event.getPlayer();
        Location loc = event.getClickedBlock().getLocation();

        if (shops.containsKey(loc) && shops.get(loc) == null) { // Loja em configuração
            // Abre uma interface para configurar (simulação com inventário)
            Inventory configInv = Bukkit.createInventory(null, 27, "Configurar Loja");
            ItemStack itemSlot = new ItemStack(Material.PAPER, 1);
            ItemMeta itemMeta = itemSlot.getItemMeta();
            itemMeta.setDisplayName("Coloque o item para venda");
            itemSlot.setItemMeta(itemMeta);
            configInv.setItem(10, itemSlot);

            ItemStack buyPriceSlot = new ItemStack(Material.GOLD_INGOT, 1);
            itemMeta = buyPriceSlot.getItemMeta();
            itemMeta.setDisplayName("Preço de Compra (clique com número)");
            buyPriceSlot.setItemMeta(itemMeta);
            configInv.setItem(13, buyPriceSlot);

            ItemStack sellPriceSlot = new ItemStack(Material.IRON_INGOT, 1);
            itemMeta = sellPriceSlot.getItemMeta();
            itemMeta.setDisplayName("Preço de Venda (clique com número)");
            sellPriceSlot.setItemMeta(itemMeta);
            configInv.setItem(16, sellPriceSlot);

            player.openInventory(configInv);
            event.setCancelled(true);
        } else if (shops.containsKey(loc)) { // Loja configurada
            Shop shop = shops.get(loc);
            showShopInterface(player, shop, loc);
            event.setCancelled(true);
        }
    }

    // Evento para processar a configuração da loja
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("Configurar Loja")) return;

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        int slot = event.getSlot();
        Location chestLoc = findChestLocation(player); // Placeholder para encontrar o baú

        if (slot == 10) { // Item para venda
            if (event.getCursor() != null && event.getCursor().getType() != Material.AIR) {
                event.setCancelled(true);
                ItemStack item = event.getCursor().clone();
                event.setCursor(null);
                shops.put(chestLoc, new Shop(player.getUniqueId(), item, 0.0, 0.0)); // Temporário
                player.sendMessage("§eItem configurado! Agora defina os preços.");
            }
        } else if (slot == 13 || slot == 16) { // Preço de compra ou venda
            event.setCancelled(true);
            if (event.isLeftClick() && event.getCursor().getType() == Material.PAPER) {
                String priceInput = player.getInventory().getItemInMainHand().getItemMeta().getDisplayName().replace("Preço: ", "");
                try {
                    double price = Double.parseDouble(priceInput);
                    Shop shop = shops.get(chestLoc);
                    if (shop != null) {
                        if (slot == 13) {
                            shop = new Shop(shop.getOwner(), shop.getItem(), price, shop.getSellPrice());
                            player.sendMessage("§aPreço de compra definido para " + price + "$!");
                        } else {
                            shop = new Shop(shop.getOwner(), shop.getItem(), shop.getBuyPrice(), price);
                            player.sendMessage("§aPreço de venda definido para " + price + "$!");
                        }
                        shops.put(chestLoc, shop);

                        // Salva no banco de dados
                        updateShopInDatabase(chestLoc, shop);
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage("§cDigite um número válido para o preço!");
                }
            }
        }

        event.setCancelled(true);
    }

    // Método para exibir a interface de compra/venda da loja
    private void showShopInterface(Player player, Shop shop, Location loc) {
        Inventory shopInv = Bukkit.createInventory(null, 27, "Loja de " + Bukkit.getOfflinePlayer(shop.getOwner()).getName());
        shopInv.setItem(13, shop.getItem());

        ItemStack buyButton = new ItemStack(Material.EMERALD);
        ItemMeta buyMeta = buyButton.getItemMeta();
        buyMeta.setDisplayName("Comprar por " + shop.getBuyPrice() + "$");
        buyButton.setItemMeta(buyMeta);
        shopInv.setItem(11, buyButton);

        ItemStack sellButton = new ItemStack(Material.REDSTONE);
        ItemMeta sellMeta = sellButton.getItemMeta();
        sellMeta.setDisplayName("Vender por " + shop.getSellPrice() + "$");
        sellButton.setItemMeta(sellMeta);
        shopInv.setItem(15, sellButton);

        player.openInventory(shopInv);
    }

    // Método para processar compra/venda
    @EventHandler
    public void onShopInteraction(InventoryClickEvent event) {
        if (!event.getView().getTitle().startsWith("Loja de ")) return;

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String title = event.getView().getTitle();
        String ownerName = title.replace("Loja de ", "");
        OfflinePlayer ownerOffline = Bukkit.getOfflinePlayer(ownerName);
        UUID owner = ownerOffline.getUniqueId();
        Location loc = findShopLocation(owner); // Placeholder para encontrar a loja

        Shop shop = shops.get(loc);
        if (shop == null) return;

        if (clicked.getType() == Material.EMERALD) { // Comprar
            double cost = shop.getBuyPrice();
            if (economyManager.getBalance(player) >= cost) {
                economyManager.removeBalance(player, cost);
                economyManager.addBalance(ownerOffline, cost * (1 - getTaxRate()));
                applyTaxToNation(owner, cost * getTaxRate());
                player.getInventory().addItem(shop.getItem().clone());
                player.sendMessage("§aVocê comprou " + shop.getItem().getType().name() + " por " + cost + "$!");
            } else {
                player.sendMessage("§cVocê não tem dólares suficientes!");
            }
        } else if (clicked.getType() == Material.REDSTONE) { // Vender
            if (player.getInventory().containsAtLeast(shop.getItem(), 1)) {
                double profit = shop.getSellPrice();
                economyManager.removeBalance(ownerOffline, profit * (1 - getTaxRate()));
                economyManager.addBalance(player, profit * (1 - getTaxRate()));
                applyTaxToNation(owner, profit * getTaxRate());
                player.getInventory().removeItem(shop.getItem());
                player.sendMessage("§aVocê vendeu " + shop.getItem().getType().name() + " por " + profit * (1 - getTaxRate()) + "$!");
            } else {
                player.sendMessage("§cVocê não tem o item para vender!");
            }
        }

        event.setCancelled(true);
    }

    // Método para aplicar imposto ao tesouro da nação
    private void applyTaxToNation(UUID owner, double tax) {
        String nation = nationManager.getPlayerNation(Bukkit.getPlayer(owner)); // Usar Player se online, ou null
        if (nation == null) {
            // Tentar buscar a nação do OfflinePlayer
            for (UUID uuid : nationManager.getPlayerNations().keySet()) {
                if (uuid.equals(owner)) {
                    nation = nationManager.getPlayerNations().get(uuid);
                    break;
                }
            }
        }
        if (nation != null) {
            try {
                PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                        "UPDATE nations SET treasury = treasury + ? WHERE name = ?");
                stmt.setDouble(1, tax);
                stmt.setString(2, nation);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("§cErro ao aplicar imposto: " + e.getMessage());
            }
        }
    }

    // Método para obter a taxa de imposto (configurável)
    private double getTaxRate() {
        return plugin.getConfig().getDouble("shop-tax-rate", 10.0) / 100.0;
    }

    // Método para encontrar a localização de um baú (placeholder)
    private Location findChestLocation(Player player) {
        // Placeholder: implementaremos para encontrar o baú mais recente colocado
        for (Location loc : shops.keySet()) {
            if (shops.get(loc) == null) { // Loja em configuração
                return loc;
            }
        }
        return null; // Retorna null por enquanto
    }

    // Método para encontrar a localização de uma loja (placeholder)
    private Location findShopLocation(UUID owner) {
        for (Location loc : shops.keySet()) {
            Shop shop = shops.get(loc);
            if (shop != null && shop.getOwner().equals(owner)) {
                return loc;
            }
        }
        return null;
    }

    // Método para atualizar uma loja no banco de dados
    private void updateShopInDatabase(Location loc, Shop shop) {
        try {
            PreparedStatement stmt = databaseManager.getConnection().prepareStatement(
                    "UPDATE shops SET item_type = ?, buy_price = ?, sell_price = ? WHERE world = ? AND x = ? AND y = ? AND z = ?");
            stmt.setString(1, shop.getItem().getType().name());
            stmt.setDouble(2, shop.getBuyPrice());
            stmt.setDouble(3, shop.getSellPrice());
            stmt.setString(4, loc.getWorld().getName());
            stmt.setInt(5, loc.getBlockX());
            stmt.setInt(6, loc.getBlockY());
            stmt.setInt(7, loc.getBlockZ());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("§cErro ao atualizar loja: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando só pode ser usado por jogadores!");
            return true;
        }

        Player player = (Player) sender;
        if (command.getName().equalsIgnoreCase("shop")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("history")) {
                // Implementar lógica para mostrar histórico de transações
                player.sendMessage("§cFuncionalidade de histórico ainda não implementada!");
                return true;
            } else {
                player.sendMessage("§cUso: /shop history");
                return true;
            }
        }
        return false;
    }
}