package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Player files written soon after a booking took goods or notes out of the inventory: the booking commits at once, the
 * player file only at the next autosave, so a hard crash in between would hand the items back while the money stays.
 * One save per player and second instead of one synchronous disk write per click. Main thread only.
 */
public final class PlayerSaves {

    private PlayerSaves() {}

    private static final Set<UUID> dirty = new HashSet<>();

    /** Main thread: the player file is written within a second. */
    public static void soon(@NotNull Player player) {
        dirty.add(player.getUniqueId());
    }

    public static void start() {
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), PlayerSaves::flush, 20L, 20L);
    }

    private static void flush() {
        if (dirty.isEmpty()) return;
        List<UUID> due = List.copyOf(dirty);
        dirty.clear();
        for (UUID uuid : due) {
            // a player who left was saved by the server on quit
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.saveData();
        }
    }

}
