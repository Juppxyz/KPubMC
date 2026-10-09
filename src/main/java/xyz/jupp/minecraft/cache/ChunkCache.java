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


    /** Claimed chunks of a team. */
    public int countClaims(@NotNull String teamID) {
        int count = 0;
        for (ChunkCacheObject claim : chunkCache.values()) {
            if (teamID.equals(claim.getTeamID())) count++;
        }
        return count;
    }

    /** A dissolved team: its claims are gone (the database removed them with the team). */
    public void forgetTeam(@NotNull String teamID) {
        chunkCache.values().removeIf(claim -> teamID.equals(claim.getTeamID()));
    }

    /** After Teams.claim wrote the claim to the database. */
    public void claimed(@NotNull String teamID, @NotNull String worldName, int x, int z) {
        chunkCache.put(new ChunkKey(worldName, x, z), new ChunkCacheObject(teamID));
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
