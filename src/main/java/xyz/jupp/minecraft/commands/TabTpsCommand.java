package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.TabListUtil;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

// TEMPORARY debug command: /tabtps shows the real TPS in the own tab header, refreshed every minute.
// To remove: delete this class, its line in Main + plugin.yml and the debug hook in TabListUtil.header.
public class TabTpsCommand implements CommandExecutor {

    // main thread only
    private static final Set<UUID> WATCHERS = new HashSet<>();
    private static BukkitTask task;

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Main.getChatPrefix() + debugText());
            return true;
        }
        if (!PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }

        if (WATCHERS.remove(player.getUniqueId())) {
            if (WATCHERS.isEmpty() && task != null) {
                task.cancel();
                task = null;
            }
            player.sendMessage(Main.getChatPrefix() + "TPS in der Tab-Liste §caus§f.");
        } else {
            WATCHERS.add(player.getUniqueId());
            if (task == null) {
                task = Bukkit.getScheduler().runTaskTimer(Main.getInstance(), TabTpsCommand::refresh, 20L * 60, 20L * 60);
            }
            player.sendMessage(Main.getChatPrefix() + "TPS in der Tab-Liste §aan §7(jede Minute aktualisiert)§f.");
        }
        TabListUtil.updateTabFor(player);
        return true;
    }

    private static void refresh() {
        for (UUID uuid : WATCHERS) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) TabListUtil.updateTabFor(player);
        }
    }

    public static boolean isWatching(Player player) {
        return WATCHERS.contains(player.getUniqueId());
    }

    // e.g. "19,98 TPS §7(5m 19,95 · 15m 19,90) §8| §f12,3 ms"
    public static String debugText() {
        double[] tps = Bukkit.getTPS();
        return "§f" + tps(tps[0]) + " TPS §7(5m " + tps(tps[1]) + " · 15m " + tps(tps[2]) + ") §8| §f"
                + String.format(Locale.GERMAN, "%.1f", Bukkit.getAverageTickTime()) + " ms";
    }

    private static String tps(double value) {
        return String.format(Locale.GERMAN, "%.2f", Math.min(20.0, value));
    }
}
