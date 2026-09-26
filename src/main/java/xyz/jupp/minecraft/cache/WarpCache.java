package xyz.jupp.minecraft.cache;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.WarpRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WarpCache {

    // The Warp Cache is standalone and not integrated in the main CacheHandler.

    private final static WarpCache instance = new WarpCache();
    public static WarpCache getInstance() {
        return instance;
    }

    // warp owner -> warp, loaded completely in onEnable
    private final ConcurrentHashMap<UUID, WarpCacheObject> warpCache = new ConcurrentHashMap<>();

    // blocking, called once in onEnable
    public int load() {
        Map<UUID, WarpCacheObject> loaded = new HashMap<>();
        for (WarpRepository.WarpData warp : WarpRepository.getAll()) {
            loaded.put(warp.owner(), new WarpCacheObject(warp.x(), warp.y(), warp.z(), warp.world()));
        }
        warpCache.clear();
        warpCache.putAll(loaded);
        return warpCache.size();
    }

    // snapshot of all warp owners
    public List<UUID> getWarpOwners() {
        return new ArrayList<>(warpCache.keySet());
    }

    public @Nullable WarpCacheObject getWarp(@NotNull UUID owner) {
        return warpCache.get(owner);
    }

    public boolean hasWarp(@NotNull UUID owner) {
        return warpCache.containsKey(owner);
    }

    public int size() {
        return warpCache.size();
    }

    public void addNewPlayerWarp(@NotNull Player player) {
        WarpCacheObject warpCacheObject = createWarpCacheObject(player);
        if (!warpCache.containsKey(player.getUniqueId())) {
            WarpRepository.createNewPlayerWarp(player);
            warpCache.putIfAbsent(player.getUniqueId(), warpCacheObject);
        }
    }

    public void removePlayerWarp(@NotNull Player player) {
        if (warpCache.containsKey(player.getUniqueId())) {
            WarpRepository.removePlayerWarp(player);
            warpCache.remove(player.getUniqueId());
        }
    }

    public void updatePlayerWarp(@NotNull Player player) {
        if (warpCache.containsKey(player.getUniqueId())) {
            WarpRepository.updatePlayerWarp(player);

            WarpCacheObject warpCacheObject = createWarpCacheObject(player);
            warpCache.computeIfPresent(player.getUniqueId(), (owner, oldWarp) -> warpCacheObject);
        }
    }

    private static WarpCacheObject createWarpCacheObject(@NotNull Player player) {
        Location location = player.getLocation();
        return new WarpCacheObject(location.getX(), location.getY(), location.getZ(), player.getWorld().getName());
    }

}
