package xyz.jupp.minecraft.perf;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /leistung: measures what the server really does while people play (admins and the console).
 * start [seconds] [aufnahme] · stop · status · berichte
 */
public class PerfCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> start(sender, args);
            case "stop" -> stop(sender);
            case "berichte" -> reports(sender);
            case "status" -> status(sender);
            default -> help(sender);
        }
        return true;
    }

    private void start(CommandSender sender, String[] args) {
        PerfSession running = PerfSession.current();
        if (running != null) {
            send(sender, "§cEs läuft schon eine Messung §7(von " + running.startedBy + "). §f/leistung stop §7beendet sie.");
            return;
        }
        int seconds = PerfSession.DEFAULT_SECONDS;
        boolean keep = false;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i].toLowerCase(Locale.ROOT);
            if (arg.equals("aufnahme")) {
                keep = true;
            } else {
                try {
                    seconds = Integer.parseInt(arg.endsWith("s") ? arg.substring(0, arg.length() - 1) : arg);
                } catch (NumberFormatException e) {
                    send(sender, "§cUnbekannt: §f" + args[i] + "§7. Beispiel: §f/leistung start 300");
                    return;
                }
            }
        }
        PerfSession session = PerfSession.start(sender, seconds, keep);
        if (session == null) {
            send(sender, "§cEs läuft schon eine Messung.");
            return;
        }
        send(sender, "§aMessung läuft §7für §f" + session.seconds + " s§7. Einfach normal weiterspielen, danach kommt der Bericht. "
                + "§f/leistung stop §7beendet sie früher.");
    }

    private void stop(CommandSender sender) {
        PerfSession running = PerfSession.current();
        if (running == null) {
            send(sender, "§7Es läuft keine Messung.");
            return;
        }
        running.stop(sender);
    }

    private void status(CommandSender sender) {
        double[] tps = Bukkit.getTPS();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        send(sender, "§7TPS §f" + String.format(Locale.GERMANY, "%.2f", Math.min(20, tps[0])) + " §7(5 min "
                + String.format(Locale.GERMANY, "%.2f", Math.min(20, tps[1])) + ") · Tick Ø §f"
                + PerfReport.ms(Bukkit.getAverageTickTime()) + " ms §7· §f" + Bukkit.getOnlinePlayers().size() + " §7Spieler");
        PerfSession running = PerfSession.current();
        String measuring = running == null ? "§7Genaue Messung mit Aufschlüsselung nach Plugins: §f/leistung start [Sekunden]"
                : "§eMessung läuft: §f" + Duration.between(running.startedAt, Instant.now()).toSeconds() + "/" + running.seconds
                + " s §7(von " + running.startedBy + ")";
        // the CPU load can block for a while in native code (Windows): on a worker
        Tasks.supplyAsync(() -> {
            com.sun.management.OperatingSystemMXBean os = PerfSession.os();
            if (os == null) return "?";
            double cores = Math.max(0, os.getProcessCpuLoad()) * Runtime.getRuntime().availableProcessors();
            return String.format(Locale.GERMANY, "%.1f Kerne", cores);
        }, cpu -> {
            send(sender, "§7CPU Java §f" + cpu + " §7von " + Runtime.getRuntime().availableProcessors() + " · RAM §f"
                    + heap.getUsed() / (1024 * 1024) + " §7von §f" + heap.getMax() / (1024 * 1024) + " MB");
            send(sender, measuring);
        });
    }

    private void reports(CommandSender sender) {
        Path folder = PerfSession.FOLDER;
        Tasks.supplyAsync(() -> {
            if (!Files.isDirectory(folder)) return List.<String>of();
            try (var files = Files.list(folder)) {
                return files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("bericht-"))
                        .sorted((a, b) -> b.compareTo(a)).limit(8).toList();
            } catch (Exception e) {
                return List.<String>of();
            }
        }, names -> {
            if (names.isEmpty()) {
                send(sender, "§7Noch keine Berichte. §f/leistung start §7misst.");
                return;
            }
            send(sender, "§6Letzte Berichte §7(in plugins/kpub/leistung):");
            for (String name : names) send(sender, " §f" + name);
        });
    }

    private void help(CommandSender sender) {
        send(sender, "§6/leistung §7– was der Server gerade wirklich tut");
        send(sender, " §f/leistung status §7– TPS, Tick-Zeit, CPU, RAM jetzt");
        send(sender, " §f/leistung start [Sekunden] [aufnahme] §7– messen (Standard " + PerfSession.DEFAULT_SECONDS
                + " s, " + PerfSession.MIN_SECONDS + "–" + PerfSession.MAX_SECONDS + "), danach Bericht nach Plugins");
        send(sender, " §f/leistung stop §7– Messung früher beenden");
        send(sender, " §f/leistung berichte §7– die letzten Berichte");
    }

    private static void send(CommandSender sender, String line) {
        sender.sendMessage(Text.section(Main.getChatPrefix() + line));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) return List.of();
        List<String> options = switch (args.length) {
            case 1 -> List.of("start", "stop", "status", "berichte");
            case 2 -> args[0].equalsIgnoreCase("start") ? List.of("60", "120", "300", "600") : List.of();
            case 3 -> args[0].equalsIgnoreCase("start") ? List.of("aufnahme") : List.of();
            default -> List.of();
        };
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) if (option.startsWith(typed)) result.add(option);
        return result;
    }
}
