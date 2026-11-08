package xyz.jupp.minecraft.inventory;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Menus that load from the database on a right click (the NPCs): a modified client sends dozens of clicks per second,
 * each would start its own round of queries and starve the connection pool for everyone. A player gets one load per
 * short while; commands are already limited by the server's spam protection.
 */
public final class Loading {

    private Loading() {}

    private static final long COOLDOWN_MILLIS = 500;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    /** Main thread: true if the player may start a menu load now (and marks it). */
    public static boolean allow(@NotNull UUID player) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(player);
        if (last != null && now - last < COOLDOWN_MILLIS) return false;
        LAST.put(player, now);
        return true;
    }

    /** On quit. */
    public static void forget(@NotNull UUID player) {
        LAST.remove(player);
    }

}
