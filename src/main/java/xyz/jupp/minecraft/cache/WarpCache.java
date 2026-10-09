package xyz.jupp.minecraft.cache;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.WarpRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player warps (one per player), loaded completely in onEnable; changes are written through (blocking).
 * The positions are passed in: they are read on the main thread, the writes run on a worker.
 */
public class WarpCache {

    private final static WarpCache instance = new WarpCache();
    public static WarpCache getInstance() {
        return instance;
    }

    // warp owner -> warp
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

    /** Blocking: false if the player has a warp already. */
    public boolean create(@NotNull UUID owner, @NotNull Location location) {
        if (warpCache.containsKey(owner) || !WarpRepository.create(owner, location)) return false;
        warpCache.put(owner, of(location));
        return true;
    }

    /** Blocking: false if the player has no warp. */
    public boolean move(@NotNull UUID owner, @NotNull Location location) {
        if (!warpCache.containsKey(owner) || !WarpRepository.update(owner, location)) return false;
        warpCache.put(owner, of(location));
        return true;
    }

    /** Blocking. */
    public void remove(@NotNull UUID owner) {
        if (warpCache.containsKey(owner)) {
            WarpRepository.remove(owner);
            warpCache.remove(owner);
        }
    }

    private static WarpCacheObject of(@NotNull Location location) {
        return new WarpCacheObject(location.getX(), location.getY(), location.getZ(), location.getWorld().getName());
    }

}
