package xyz.jupp.minecraft.cache;

import org.bson.Document;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.ChunkCollection;

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
        for (Document document : ChunkCollection.getAllChunksFromDatabase()) {
            try {
                String worldName = document.getString("worldName");
                String teamID = document.getString("teamID");
                int x = document.getInteger("x");
                int z = document.getInteger("z");
                loaded.put(new ChunkKey(worldName, x, z), new ChunkCacheObject(teamID, genChunkID(worldName, x, z)));
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Skipping invalid chunk document {}: {}", document.get("_id"), e.toString());
            }
        }
        chunkCache.clear();
        chunkCache.putAll(loaded);
        return chunkCache.size();
    }

    private static String genChunkID(String worldName, int x, int z) {
        return worldName + ":" + x + ":" + z;
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
        ChunkCacheObject chunkCacheObject = new ChunkCacheObject(teamID, genChunkID(worldName, x, z));
        if (chunkCache.putIfAbsent(key, chunkCacheObject) != null) {
            return false;
        }
        try {
            ChunkCollection.createChunkInDatabase(teamID, chunkCacheObject.getChunkID(), worldName, x, z);
        } catch (RuntimeException e) {
            chunkCache.remove(key, chunkCacheObject);
            throw e;
        }
        return true;
    }

    public boolean removeChunk(@Nullable String teamID, @NotNull String worldName, int x, int z) {
        ChunkKey key = new ChunkKey(worldName, x, z);
        ChunkCacheObject cco = chunkCache.get(key);
        if (cco == null || cco.getTeamID() == null) return false;
        if (!cco.getTeamID().equals(teamID)) return false;

        ChunkCollection.removeChunkInDatabase(cco.getTeamID(), cco.getChunkID());
        chunkCache.remove(key, cco);
        return true;
    }

}
