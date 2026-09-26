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
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JailCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = Main.getChatPrefix();
    private static final List<String> SUBCOMMANDS = List.of("jail", "unjail", "wanted");
    private static final List<String> WANTED_MODES = List.of("on", "off", "toggle");

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

        // next tick, so the answers still follow the usage line Bukkit sends for 'return false';
        // only the JailHandler calls (database writes) run async
        Tasks.sync(() -> {
            switch (args[0].toLowerCase()) {
                case "jail" -> handleJail(sender, label, args);
                case "unjail" -> handleUnjail(sender, label, args);
                case "wanted" -> handleWanted(sender, label, args);
                default -> sendHelp(sender, label);
            }
        });
        return false;
    }

    // /bestrafung jail <Spieler> <Stunden> <Grund...>
    private void handleJail(CommandSender sender, String label, String[] args) {
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

        Tasks.async(() -> {
            JailHandler.jailPlayer(target, hours, reason);
            Tasks.sync(() -> sender.sendMessage(PREFIX + "§a" + target.getName() + " §fwurde für §e" + hours + "§f Stunde(n) inhaftiert. Grund: §7" + reason));
        });
    }

    // /bestrafung unjail <Spieler>
    private void handleUnjail(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "§fNutze: §e/" + label + " unjail <Spieler>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + "§cSpieler nicht gefunden oder nicht online.");
            return;
        }

        Tasks.async(() -> {
            JailHandler.releasePlayer(target);
            Tasks.sync(() -> sender.sendMessage(PREFIX + "§a" + target.getName() + " §fwurde aus dem Gefängnis entlassen."));
        });
    }

    // /bestrafung wanted <Spieler> <on|off|toggle>
    private void handleWanted(CommandSender sender, String label, String[] args) {
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
            case "on" -> newState = true;
            case "off" -> newState = false;
            case "toggle" -> newState = !JailHandler.isPlayerWanted(target);
            default -> {
                sender.sendMessage(PREFIX + "§cUnbekannter Modus: §f" + mode + " §7(§fon|off|toggle§7)");
                return;
            }
        }

        Tasks.async(() -> {
            JailHandler.setPlayerWanted(target, newState);

            if (newState) {
                Tasks.sync(() -> {
                    JailHandler.playerWantedBroadcast(target, "Manuelle Fahndung");
                    sender.sendMessage(PREFIX + "§a" + target.getName() + " §fist nun §4§lGESUCHT§f.");
                });
            } else {
                Tasks.sync(() -> sender.sendMessage(PREFIX + "§a" + target.getName() + " §fist nicht länger §4§lGESUCHT§f."));
                CacheHandler.getInstance().getPlayerInCache(target).unsetJail(false);
            }
        });
    }


    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§8§m------------§r §6Bestrafung §8§m------------");
        sender.sendMessage("§e/" + label + " jail <Spieler> <Stunden> <Grund...> §7- Spieler inhaftieren");
        sender.sendMessage("§e/" + label + " unjail <Spieler> §7- Spieler freilassen");
        sender.sendMessage("§e/" + label + " wanted <Spieler> <on|off|toggle> §7- Wanted-Status setzen");
        sender.sendMessage("§8§m--------------------------------------");
    }


    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {

        if (args.length == 1) {
            return startingWith(SUBCOMMANDS, args[0]);
        }

        if (args.length == 2) {
            List<String> completions = new ArrayList<>();
            String current = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(current)) {
                    completions.add(p.getName());
                }
            }
            return completions;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("wanted")) {
            return startingWith(WANTED_MODES, args[2]);
        }

        return new ArrayList<>();
    }

    private static List<String> startingWith(List<String> options, String input) {
        List<String> completions = new ArrayList<>();
        String current = input.toLowerCase();
        for (String option : options) {
            if (option.startsWith(current)) {
                completions.add(option);
            }
        }
        return completions;
    }
}
