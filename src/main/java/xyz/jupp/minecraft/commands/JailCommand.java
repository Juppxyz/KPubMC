package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.utils.JailHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JailCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = Main.getChatPrefix();

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!sender.isOp()) {
            sender.sendMessage(PREFIX + "§cDafür hast du keine Berechtigung.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            String sub = args[0].toLowerCase();
            switch (sub) {
                case "jail":
                    handleJail(sender, label, args);
                    return;
                case "unjail":
                    handleUnjail(sender, label, args);
                    return;
                case "wanted":
                    handleWanted(sender, label, args);
                    return;
                default:
                    sendHelp(sender, label);
                    return;
            }
        });
        return false;
    }

    /* ==========================
     * Subcommand: /justice jail <Player> <Hours> <Reason...>
     * ========================== */

    private void handleJail(CommandSender sender, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(PREFIX + "§cDu darfst niemanden einsperren.");
            return;
        }

        if (args.length < 4) {
            sender.sendMessage(PREFIX + "§fNutze: §e/" + label + " jail <Spieler> <Stunden> <Grund...>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + "§cSpieler nicht gefunden oder nicht online.");
            return;
        }

        int hours;
        try {
            hours = Integer.parseInt(args[2]);
            if (hours <= 0) {
                sender.sendMessage(PREFIX + "§cDie Stundenanzahl muss > 0 sein.");
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(PREFIX + "§cUngültige Stundenanzahl: §f" + args[2]);
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

        JailHandler.jailPlayer(target, hours, reason);

        sender.sendMessage(PREFIX + "§a" + target.getName() + " §fwurde für §e" + hours + "§f Stunde(n) inhaftiert. Grund: §7" + reason);
    }

    /* ==========================
     * Subcommand: /justice unjail <Player>
     * ========================== */

    private void handleUnjail(CommandSender sender, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(PREFIX + "§cDu darfst niemanden entlassen.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(PREFIX + "§fNutze: §e/" + label + " unjail <Spieler>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + "§cSpieler nicht gefunden oder nicht online.");
            return;
        }

        JailHandler.releasePlayer(target);
        sender.sendMessage(PREFIX + "§a" + target.getName() + " §fwurde aus dem Gefängnis entlassen.");
    }

    /* ==========================
     * Subcommand: /justice wanted <Player> <on|off|toggle>
     * ========================== */

    private void handleWanted(CommandSender sender, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(PREFIX + "§cDu darfst Wanted-Status nicht ändern.");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§fNutze: §e/" + label + " wanted <Spieler> <on|off|toggle>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + "§cSpieler nicht gefunden oder nicht online.");
            return;
        }

        String mode = args[2].toLowerCase();
        boolean newState;

        switch (mode) {
            case "on":
                newState = true;
                break;
            case "off":
                newState = false;
                break;
            case "toggle":
                newState = !JailHandler.isPlayerWanted(target);
                break;
            default:
                sender.sendMessage(PREFIX + "§cUnbekannter Modus: §f" + mode + " §7(§fon|off|toggle§7)");
                return;
        }

        JailHandler.setPlayerWanted(target, newState);
        PlayerCacheObject tco = CacheHandler.getInstance().getPlayerInCache(target);

        if (newState) {
            JailHandler.playerWantedBroadcast(target, "Manuelle Fahndung");
            sender.sendMessage(PREFIX + "§a" + target.getName() + " §fist nun §4§lGESUCHT§f.");
        } else {
            sender.sendMessage(PREFIX + "§a" + target.getName() + " §fist nicht länger §4§lGESUCHT§f.");
            tco.unsetJail(false);
        }
    }



    /* ==========================
     * Hilfe
     * ========================== */

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§8§m------------§r §6Bestrafung §8§m------------");
        sender.sendMessage("§e/" + label + " jail <Spieler> <Stunden> <Grund...> §7- Spieler inhaftieren");
        sender.sendMessage("§e/" + label + " unjail <Spieler> §7- Spieler freilassen");
        sender.sendMessage("§e/" + label + " wanted <Spieler> <on|off|toggle> §7- Wanted-Status setzen");
        sender.sendMessage("§8§m--------------------------------------");
    }

    /* ==========================
     * Tab-Completion
     * ========================== */

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> base = new ArrayList<>();
            base.add("jail");
            base.add("unjail");
            base.add("wanted");

            String current = args[0].toLowerCase();
            for (String s : base) {
                if (s.startsWith(current)) {
                    completions.add(s);
                }
            }
            return completions;
        }

        if (args.length == 2) {
            String current = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(current)) {
                    completions.add(p.getName());
                }
            }
            return completions;
        }

        // /justice wanted <Spieler> <on|off|toggle>
        if (args.length == 3 && args[0].equalsIgnoreCase("wanted")) {
            List<String> base = Arrays.asList("on", "off", "toggle");
            String current = args[2].toLowerCase();
            for (String s : base) {
                if (s.startsWith(current)) {
                    completions.add(s);
                }
            }
            return completions;
        }

        return completions;
    }
}
