package xyz.jupp.minecraft.cache;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PlayerCache {

    private PlayerCache() {}

    private static final ConcurrentHashMap<UUID, PlayerCacheObject> playerCacheMap = new ConcurrentHashMap<>();

    // Returns the cached object, loads it synchronously if it was not preloaded. Never null.
    static PlayerCacheObject getPlayer(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        PlayerCacheObject cached = playerCacheMap.get(uuid);
        if (cached == null) {
            PlayerCacheObject loaded = PlayerCacheObject.load(uuid);
            if (!player.isOnline()) {
                // late task for a player who already left: answer, but do not cache again
                loaded.attach(player);
                return loaded;
            }
            cached = playerCacheMap.putIfAbsent(uuid, loaded);
            if (cached == null) {
                cached = loaded;
                if (!player.isOnline()) playerCacheMap.remove(uuid, loaded);
            }
        }
        cached.attach(player);
        return cached;
    }

    // blocking, used on the async pre-login thread; replaces a possibly stale entry
    static void preload(@NotNull UUID uuid) {
        PlayerCacheObject loaded = PlayerCacheObject.load(uuid);
        loaded.markPreloaded();
        playerCacheMap.put(uuid, loaded);
    }

    // null if the player is not cached, never loads
    static @Nullable PlayerCacheObject getIfCached(@NotNull UUID uuid) {
        return playerCacheMap.get(uuid);
    }

    /**
     * Removes the entry of this session only. On a reconnect the new login is preloaded before the old session quits;
     * that fresh entry (not attached yet, or attached to the new Player) stays, so the join does not hit the database.
     */
    static void removeSession(@NotNull Player quitting) {
        playerCacheMap.computeIfPresent(quitting.getUniqueId(), (uuid, cached) -> {
            Player attached = cached.attached();
            // the newer session's entry
            if (attached != null && attached != quitting) return cached;
            // preloaded for a login after this session began (the old session may have touched it in between)
            if (cached.preloaded() && cached.loadedAt() > quitting.getLastLogin()) return cached;
            return null;
        });
    }

    /**
     * Main thread: drops entries nobody uses any more, a preload whose login was refused afterwards (ban, whitelist,
     * full server) or a session that left without its quit reaching the cache. Returns how many.
     */
    static int sweep(long unattachedOlderThanMillis) {
        long cutoff = System.currentTimeMillis() - unattachedOlderThanMillis;
        int removed = 0;
        for (Map.Entry<UUID, PlayerCacheObject> entry : playerCacheMap.entrySet()) {
            PlayerCacheObject cached = entry.getValue();
            Player attached = cached.attached();
            // old and not used by an online session: a fresh preload (a login still in its configuration phase, or a
            // reconnect the old session touched before it quit) is young and stays
            boolean unused = (attached == null || !attached.isOnline()) && cached.loadedAt() < cutoff;
            if (unused && Bukkit.getPlayer(entry.getKey()) == null && playerCacheMap.remove(entry.getKey(), cached)) removed++;
        }
        return removed;
    }

}
