package xyz.jupp.minecraft.cache;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.ChunkRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkCache {

    private final static ChunkCache instance = new ChunkCache();
    public static ChunkCache getInstance() {
        return instance;
    }

    private record ChunkKey(String worldName, int x, int z) {}

    // claimed chunks, loaded completely in onEnable
    private final ConcurrentHashMap<ChunkKey, ChunkCacheObject> chunkCache = new ConcurrentHashMap<>();

    // blocking, called once in onEnable
    public int load() {
        Map<ChunkKey, ChunkCacheObject> loaded = new HashMap<>();
        for (ChunkRepository.ChunkData chunk : ChunkRepository.getAll()) {
            loaded.put(new ChunkKey(chunk.world(), chunk.x(), chunk.z()),
                    new ChunkCacheObject(chunk.teamID()));
        }
        chunkCache.clear();
        chunkCache.putAll(loaded);
        return chunkCache.size();
    }



    // chunk coordinates, not block coordinates
    public @Nullable ChunkCacheObject getClaim(@NotNull String worldName, int chunkX, int chunkZ) {
        return chunkCache.get(new ChunkKey(worldName, chunkX, chunkZ));
    }

    public @Nullable ChunkCacheObject getClaim(@NotNull World world, int chunkX, int chunkZ) {
        return getClaim(world.getName(), chunkX, chunkZ);
    }

    // computes the chunk from the block coordinates, so the chunk is never loaded
    public @Nullable ChunkCacheObject getClaim(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) return null;
        return getClaim(world.getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }


    public boolean addChunk(@NotNull String teamID, @NotNull String worldName, int x, int z) {
        ChunkKey key = new ChunkKey(worldName, x, z);
        ChunkCacheObject chunkCacheObject = new ChunkCacheObject(teamID);
        if (chunkCache.putIfAbsent(key, chunkCacheObject) != null) {
            return false;
        }
        boolean claimed;
        try {
            claimed = ChunkRepository.claim(teamID, worldName, x, z);
        } catch (RuntimeException e) {
            chunkCache.remove(key, chunkCacheObject);
            throw e;
        }
        if (!claimed) chunkCache.remove(key, chunkCacheObject);
        return claimed;
    }

    public boolean removeChunk(@Nullable String teamID, @NotNull String worldName, int x, int z) {
        ChunkKey key = new ChunkKey(worldName, x, z);
        ChunkCacheObject cco = chunkCache.get(key);
        if (cco == null || cco.getTeamID() == null) return false;
        if (!cco.getTeamID().equals(teamID)) return false;

        ChunkRepository.release(cco.getTeamID(), worldName, x, z);
        chunkCache.remove(key, cco);
        return true;
    }

}
