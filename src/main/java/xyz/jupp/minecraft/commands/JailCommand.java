package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.jail.JailCells;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JailCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = Main.getChatPrefix();
    private static final List<String> SUBCOMMANDS = List.of("jail", "unjail", "wanted", "zelle", "zellen");
    private static final List<String> WANTED_MODES = List.of("on", "off", "toggle");
    private static final List<String> CELL_ACTIONS = List.of("ecke1", "ecke2", "spawn", "löschen");

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
                case "zelle" -> handleCell(sender, label, args);
                case "zellen" -> handleCells(sender, label);
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
        if (!JailCells.anyReady()) {
            sender.sendMessage(PREFIX + "§cEs gibt noch keine fertige Zelle §7(/" + label + " zelle)§c, " + target.getName() + " bleibt an Ort und Stelle.");
        }

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


    // /bestrafung zelle <Name> <ecke1|ecke2|spawn|löschen>: a cell is ready once both corners and the spawn are set
    private void handleCell(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§fNutze: §e/" + label + " zelle <Name> <ecke1|ecke2|spawn|löschen>");
            sender.sendMessage(PREFIX + "§7Ecken: stell dich in zwei gegenüberliegende Ecken des Innenraums (zwei Blöcke");
            sender.sendMessage(PREFIX + "§7über der oberen Ecke zählen noch dazu, Springen ist keine Flucht). Spawn: deine Position.");
            return;
        }
        String name = args[1];
        String action = args[2].toLowerCase();
        if (action.equals("löschen") || action.equals("loeschen")) {
            Tasks.supplyAsync(() -> JailCells.delete(name), removal -> sender.sendMessage(PREFIX + switch (removal) {
                case OK -> "§fDie Zelle §e" + name + " §fist gelöscht.";
                case UNKNOWN -> "§cDie Zelle §f" + name + " §cgibt es nicht.";
                case OCCUPIED -> "§cIn der Zelle §f" + name + " §csitzt noch jemand.";
            }));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cZellen setzt man im Spiel, an der eigenen Position.");
            return;
        }
        if (!JailCells.NAME.matcher(name).matches()) {
            sender.sendMessage(PREFIX + "§cDer Name braucht 1 bis 16 Zeichen: Buchstaben, Zahlen, _ oder -.");
            return;
        }
        Location here = player.getLocation();
        Runnable change = switch (action) {
            case "ecke1" -> () -> JailCells.setCorner(name, 1, here);
            case "ecke2" -> () -> JailCells.setCorner(name, 2, here);
            case "spawn" -> () -> JailCells.setSpawn(name, here);
            default -> null;
        };
        if (change == null) {
            sender.sendMessage(PREFIX + "§cUnbekannt: §f" + action + " §7(ecke1, ecke2, spawn oder löschen)");
            return;
        }
        Tasks.supplyAsync(() -> {
            change.run();
            return JailCells.byName(name);
        }, cell -> {
            if (cell == null) return;
            sender.sendMessage(PREFIX + "§fZelle §e" + cell.name() + "§f: §a" + action + " gesetzt§f. " + cellStatus(cell));
        });
    }

    // /bestrafung zellen
    private void handleCells(CommandSender sender, String label) {
        Tasks.supplyAsync(JailCells::occupancy, counts -> {
            List<JailCells.Cell> cells = JailCells.all();
            if (cells.isEmpty()) {
                sender.sendMessage(PREFIX + "§7Es gibt noch keine Zellen. §fAnlegen mit §e/" + label + " zelle <Name> ecke1|ecke2|spawn");
                return;
            }
            sender.sendMessage(PREFIX + "§fZellen §7(neue Häftlinge kommen in die leerste)§8:");
            for (JailCells.Cell cell : cells) {
                sender.sendMessage("§8» §e" + cell.name() + " §7" + cell.world() + " " + cellStatus(cell)
                        + " §8· §f" + counts.getOrDefault(cell.id(), 0) + " §7Häftlinge");
            }
        });
    }

    private static String cellStatus(JailCells.Cell cell) {
        if (cell.ready()) return "§a(fertig)";
        List<String> missing = new ArrayList<>();
        if (cell.corner1() == null) missing.add("ecke1");
        if (cell.corner2() == null) missing.add("ecke2");
        if (cell.spawn() == null) missing.add("spawn");
        if (missing.isEmpty()) return "§c(der Spawn liegt nicht in der Zelle)";
        return "§7(fehlt: " + String.join(", ", missing) + ")";
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§8§m------------§r §6Bestrafung §8§m------------");
        sender.sendMessage("§e/" + label + " jail <Spieler> <Stunden> <Grund...> §7- Spieler inhaftieren");
        sender.sendMessage("§e/" + label + " unjail <Spieler> §7- Spieler freilassen");
        sender.sendMessage("§e/" + label + " wanted <Spieler> <on|off|toggle> §7- Wanted-Status setzen");
        sender.sendMessage("§e/" + label + " zelle <Name> <ecke1|ecke2|spawn|löschen> §7- Zelle einrichten");
        sender.sendMessage("§e/" + label + " zellen §7- alle Zellen mit Häftlingen");
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

        if (args.length == 2 && args[0].equalsIgnoreCase("zelle")) {
            return startingWith(JailCells.all().stream().map(JailCells.Cell::name).toList(), args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("zelle")) {
            return startingWith(CELL_ACTIONS, args[2]);
        }

        if (args.length == 2 && !args[0].equalsIgnoreCase("zellen")) {
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
