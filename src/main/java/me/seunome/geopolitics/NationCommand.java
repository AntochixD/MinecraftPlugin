package me.seunome.geopolitics;

import me.seunome.MeuPrimeiroPlugin;
import me.seunome.utils.MapManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class NationCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final NationManager nationManager;
    private final MapManager mapManager;
    private final AllianceManager allianceManager;
    private final CityManager cityManager; // Novo campo para CityManager

    // Construtor para receber a instância do plugin e gerenciadores
    public NationCommand(JavaPlugin plugin, NationManager nationManager) {
        this.plugin = plugin;
        this.nationManager = nationManager;
        this.mapManager = ((MeuPrimeiroPlugin) plugin).getMapManager(); // Inicializa MapManager
        this.allianceManager = ((MeuPrimeiroPlugin) plugin).getAllianceManager(); // Inicializa AllianceManager
        this.cityManager = ((MeuPrimeiroPlugin) plugin).getCityManager(); // Inicializa CityManager
        plugin.getCommand("nation").setExecutor(this); // Registra este executor para o comando /nation
        plugin.getCommand("map").setExecutor(this); // Registra este executor para o comando /map
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando só pode ser usado por jogadores!");
            return true;
        }

        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("map")) {
            mapManager.showDynamicMap(player);
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§eUse: /nation [create|invite|kick|invite accept|invite deny|city create|cities|show|war declare|conquer|surrender|surrender accept|ally create|ally invite|ally accept|ally deny|ally leave|ally break|truce|truce accept] [nome/jogador/aliança]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create":
                if (args.length < 2) {
                    player.sendMessage("§cUse: /nation create <nome>");
                    return true;
                }
                nationManager.createNation(player, args[1]);
                return true;

            case "invite":
                if (args.length < 3) {
                    player.sendMessage("§cUse: /nation invite [accept|deny] <nome_da_nação> ou /nation invite <jogador> <nome_da_nação>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("accept") || args[1].equalsIgnoreCase("deny")) {
                    String action = args[1].toLowerCase();
                    String nationName = args[2];
                    if (action.equals("accept")) {
                        nationManager.acceptInvite(player, nationName);
                    } else if (action.equals("deny")) {
                        nationManager.denyInvite(player, nationName);
                    }
                } else {
                    Player target = plugin.getServer().getPlayer(args[1]);
                    if (target == null) {
                        player.sendMessage("§cJogador não encontrado!");
                        return true;
                    }
                    nationManager.invitePlayer(player, target, args[2]);
                }
                return true;

            case "kick":
                if (args.length < 2) {
                    player.sendMessage("§cUse: /nation kick <jogador>");
                    return true;
                }
                Player targetPlayer = plugin.getServer().getPlayer(args[1]);
                if (targetPlayer == null) {
                    player.sendMessage("§cJogador não encontrado!");
                    return true;
                }
                nationManager.kickPlayer(player, targetPlayer);
                return true;

            case "city":
                if (args.length < 3) {
                    player.sendMessage("§cUse: /nation city [create] <nome_da_cidade>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("create")) {
                    nationManager.createCity(player, args[2]);
                } else {
                    player.sendMessage("§cUse: /nation city create <nome_da_cidade>");
                }
                return true;

            case "cities":
                cityManager.openCityMenu(player);
                return true;

            case "show":
                nationManager.showNationBorders(player);
                return true;

            case "war":
                if (args.length < 3 || !args[1].equalsIgnoreCase("declare")) {
                    player.sendMessage("§cUse: /nation war declare <nome_da_nação>");
                    return true;
                }
                nationManager.declareWar(player, args[2]);
                return true;

            case "conquer":
                if (args.length < 2) {
                    player.sendMessage("§cUse: /conquer <nome_da_nação>");
                    return true;
                }
                nationManager.startConquest(player, args[1]);
                return true;

            case "surrender":
                if (args.length == 2) {
                    nationManager.surrender(player, args[1]);
                } else {
                    player.sendMessage("§cUso: /nation surrender <nação>");
                }
                return true;

            case "surrender accept":
                if (args.length == 2) {
                    nationManager.acceptSurrender(player, args[1]);
                } else {
                    player.sendMessage("§cUso: /nation surrender accept <nação>");
                }
                return true;

            case "ally":
                if (args.length < 3) {
                    player.sendMessage("§cUse: /nation ally [create|invite|accept|deny|leave|break] <nome/nação/aliança>");
                    return true;
                }
                String action = args[1].toLowerCase();
                String name = args[2];
                switch (action) {
                    case "create":
                        nationManager.createAlliance(player, name);
                        break;
                    case "invite":
                        if (args.length < 4) {
                            player.sendMessage("§cUse: /nation ally invite <nação> <nome_da_aliança>");
                            return true;
                        }
                        nationManager.inviteAlliance(player, name, args[3]);
                        break;
                    case "accept":
                        nationManager.acceptAllianceInvite(player, name);
                        break;
                    case "deny":
                        nationManager.denyAllianceInvite(player, name);
                        break;
                    case "leave":
                        nationManager.leaveAlliance(player, name);
                        break;
                    case "break":
                        nationManager.breakAlliance(player, name);
                        break;
                    default:
                        player.sendMessage("§cAção inválida! Use: /nation ally [create|invite|accept|deny|leave|break] <nome/nação/aliança>");
                        return true;
                }
                return true;

            case "truce":
                if (args.length == 3) {
                    try {
                        long durationHours = Long.parseLong(args[2]); // Duração em horas
                        nationManager.proposeTruce(player, args[1], durationHours);
                    } catch (NumberFormatException e) {
                        player.sendMessage("§cDuração deve ser um número (horas)!");
                    }
                } else {
                    player.sendMessage("§cUso: /nation truce <nação> <duração_horas>");
                }
                return true;

            case "truce accept":
                if (args.length == 2) {
                    nationManager.acceptTruce(player, args[1]);
                } else {
                    player.sendMessage("§cUso: /nation truce accept <nação>");
                }
                return true;

            default:
                player.sendMessage("§cComando inválido! Use: /nation [create|invite|kick|invite accept|invite deny|city create|cities|show|war declare|conquer|surrender|surrender accept|ally create|ally invite|ally accept|ally deny|ally leave|ally break|truce|truce accept] [nome/jogador/aliança]");
                return true;
        }
    }
}