package me.seunome.utils;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class PermissionManager {
    private final JavaPlugin plugin;
    private final LuckPerms luckPerms;

    public PermissionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.luckPerms = LuckPermsProvider.get();
        if (this.luckPerms == null) {
            plugin.getLogger().severe("§cLuckPerms não encontrado! Desativando funcionalidades de permissões.");
        }
    }

    public void addPermission(Player player, String permission) {
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                user.data().add(Node.builder(permission).value(true).build());
                luckPerms.getUserManager().saveUser(user);
                plugin.getLogger().info("§aPermissão " + permission + " adicionada a " + player.getName() + "!");
            }
        }
    }

    public void removePermission(Player player, String permission) {
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                user.data().remove(Node.builder(permission).value(true).build());
                luckPerms.getUserManager().saveUser(user);
                plugin.getLogger().info("§cPermissão " + permission + " removida de " + player.getName() + "!");
            }
        }
    }

    public boolean hasPermission(Player player, String permission) {
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
            }
        }
        return false;
    }
}