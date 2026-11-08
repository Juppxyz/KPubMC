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
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.ArrayList;
import java.util.List;

public class PlacesCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = Main.getChatPrefix();
    private static final List<String> SUBCOMMANDS = List.of("spawn", "spawnbereich", "arena", "liste");
    private static final List<String> AREA_ACTIONS = List.of("ecke1", "ecke2", "löschen");

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

        // next tick, so the answers still follow the usage line Bukkit sends for 'return false'
        Tasks.sync(() -> {
            Locations.Kind kind = kindOf(args[0]);
            if (kind != null) {
                handleArea(sender, label, args, kind);
                return;
            }
            switch (args[0].toLowerCase()) {
                case "spawn" -> handleSpawn(sender);
                case "liste" -> handleList(sender, label);
                default -> sendHelp(sender, label);
            }
        });
        return false;
    }

    private static @Nullable Locations.Kind kindOf(String subcommand) {
        return switch (subcommand.toLowerCase()) {
            case "spawnbereich" -> Locations.Kind.SPAWN;
            case "arena" -> Locations.Kind.ARENA;
            default -> null;
        };
    }

    // /ort spawn: the admin's position and view, for /spawn, first joins, released prisoners and the dead of the arena
    private void handleSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cOrte setzt man im Spiel, an der eigenen Position.");
            return;
        }
        Location here = player.getLocation();
        Tasks.async(() -> {
            Locations.setSpawn(here);
            Tasks.sync(() -> {
                sender.sendMessage(PREFIX + "§fSpawnpunkt gesetzt§8: §7" + here.getWorld().getName() + " "
                        + here.getBlockX() + " " + here.getBlockY() + " " + here.getBlockZ());
                sender.sendMessage(PREFIX + "§7Wo man ohne Bett aufwacht, bestimmt weiter /setworldspawn.");
            });
        });
    }

    // /ort spawnbereich|arena <Name> <ecke1|ecke2|löschen>: an area counts once both corners are set
    private void handleArea(CommandSender sender, String label, String[] args, Locations.Kind kind) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§fNutze: §e/" + label + " " + args[0].toLowerCase() + " <Name> <ecke1|ecke2|löschen>");
            sender.sendMessage(PREFIX + (kind == Locations.Kind.SPAWN
                    ? "§7Ecken: stell dich in zwei gegenüberliegende Ecken, der Bereich gilt in jeder Höhe."
                    : "§7Ecken: diagonal gegenüber, eine unten und eine oben (zwei Blöcke darüber zählen noch dazu)."));
            return;
        }
        String name = args[1];
        String action = args[2].toLowerCase();
        if (action.equals("löschen") || action.equals("loeschen")) {
            Tasks.supplyAsync(() -> Locations.delete(kind, name), deleted -> sender.sendMessage(PREFIX + (deleted
                    ? "§f" + kind.label() + " §e" + name + " §fist gelöscht."
                    : "§c" + kind.label() + " §f" + name + " §cgibt es nicht.")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cOrte setzt man im Spiel, an der eigenen Position.");
            return;
        }
        if (!Locations.NAME.matcher(name).matches()) {
            sender.sendMessage(PREFIX + "§cDer Name braucht 1 bis 16 Zeichen: Buchstaben, Zahlen, _ oder -.");
            return;
        }
        int corner = switch (action) {
            case "ecke1" -> 1;
            case "ecke2" -> 2;
            default -> 0;
        };
        if (corner == 0) {
            sender.sendMessage(PREFIX + "§cUnbekannt: §f" + action + " §7(ecke1, ecke2 oder löschen)");
            return;
        }
        Location here = player.getLocation();
        Tasks.supplyAsync(() -> {
            Locations.setCorner(kind, name, corner, here);
            return Locations.byName(kind, name);
        }, area -> {
            if (area == null) return;
            sender.sendMessage(PREFIX + "§f" + kind.label() + " §e" + area.name() + "§f: §a" + action + " gesetzt§f. " + areaStatus(area));
        });
    }

    // /ort liste
    private void handleList(CommandSender sender, String label) {
        Locations.Spawn spawn = Locations.spawn();
        sender.sendMessage(PREFIX + "§fOrte der Karte§8:");
        if (spawn == null) {
            sender.sendMessage("§8» §fSpawnpunkt §c(nicht gesetzt, es gilt der Spawn der Hauptwelt) §7/" + label + " spawn");
        } else {
            String world = Bukkit.getWorld(spawn.world()) == null ? " §c(Welt nicht geladen)" : "";
            sender.sendMessage("§8» §fSpawnpunkt §7" + spawn.world() + " " + (int) Math.floor(spawn.x()) + " "
                    + (int) Math.floor(spawn.y()) + " " + (int) Math.floor(spawn.z()) + world);
        }
        if (!Locations.anyReady(Locations.Kind.SPAWN)) {
            sender.sendMessage("§8» §cNoch kein fertiger Spawnbereich, der Spawn ist ungeschützt. §7/" + label + " spawnbereich");
        }
        if (!Locations.anyReady(Locations.Kind.ARENA)) {
            sender.sendMessage("§8» §7Noch keine fertige Arena. /" + label + " arena");
        }
        for (Locations.Area area : Locations.all()) {
            sender.sendMessage("§8» §f" + area.kind().label() + " §e" + area.name() + " §7" + area.world() + " "
                    + corner(area, area.corner1()) + " §8→ §7" + corner(area, area.corner2()) + " " + areaStatus(area));
        }
    }

    // a spawn area takes every height, so only x and z
    private static String corner(Locations.Area area, @Nullable int[] corner) {
        if (corner == null) return "?";
        return area.kind() == Locations.Kind.SPAWN ? corner[0] + " " + corner[2] : corner[0] + " " + corner[1] + " " + corner[2];
    }

    private static String areaStatus(Locations.Area area) {
        if (Bukkit.getWorld(area.world()) == null) return "§c(Welt nicht geladen)";
        if (area.ready()) return "§a(fertig)";
        return "§7(fehlt: " + (area.corner1() == null ? "ecke1" : "ecke2") + ")";
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§8§m------------§r §6Orte §8§m------------");
        sender.sendMessage("§e/" + label + " spawn §7- Spawnpunkt auf deine Position setzen");
        sender.sendMessage("§e/" + label + " spawnbereich <Name> <ecke1|ecke2|löschen> §7- geschützter Bereich, jede Höhe");
        sender.sendMessage("§e/" + label + " arena <Name> <ecke1|ecke2|löschen> §7- Tod ohne Itemverlust");
        sender.sendMessage("§e/" + label + " liste §7- alles, was gesetzt ist");
        sender.sendMessage("§8§m----------------------------------");
    }


    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        // admins only: spawn areas and arenas are set up by them
        if (sender instanceof Player player && !xyz.jupp.minecraft.utils.PermissionsUtil.isPlayerAdmin(player)) return List.of();

        if (args.length == 1) {
            return startingWith(SUBCOMMANDS, args[0]);
        }

        Locations.Kind kind = kindOf(args[0]);
        if (kind == null) {
            return new ArrayList<>();
        }

        if (args.length == 2) {
            return startingWith(Locations.all().stream().filter(area -> area.kind() == kind).map(Locations.Area::name).toList(), args[1]);
        }

        if (args.length == 3) {
            return startingWith(AREA_ACTIONS, args[2]);
        }

        return new ArrayList<>();
    }

    private static List<String> startingWith(List<String> options, String input) {
        List<String> completions = new ArrayList<>();
        String current = input.toLowerCase();
        for (String option : options) {
            if (option.toLowerCase().startsWith(current)) {
                completions.add(option);
            }
        }
        return completions;
    }
}
