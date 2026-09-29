package xyz.jupp.minecraft.cache;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        playerCacheMap.put(uuid, PlayerCacheObject.load(uuid));
    }

    // null if the player is not cached, never loads
    static @Nullable PlayerCacheObject getIfCached(@NotNull UUID uuid) {
        return playerCacheMap.get(uuid);
    }

    static void removePlayer(@NotNull UUID uuid) {
        playerCacheMap.remove(uuid);
    }

}
