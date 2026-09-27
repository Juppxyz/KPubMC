package xyz.jupp.minecraft.utils;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.ChunkCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;

public final class ClaimedAreaHelper {

    private ClaimedAreaHelper() {}

    // team that claimed the chunk of this block position, null in the wilderness or for an unknown team (never loads a chunk)
    public static @Nullable TeamCacheObject getClaimingTeam(@NotNull World world, int blockX, int blockZ) {
        ChunkCacheObject claim = ChunkCache.getInstance().getClaim(world, blockX >> 4, blockZ >> 4);
        return claim == null ? null : CacheHandler.getInstance().getTeamCacheObject(claim.getTeamID());
    }

    public static @Nullable TeamCacheObject getClaimingTeam(@NotNull Block block) {
        return getClaimingTeam(block.getWorld(), block.getX(), block.getZ());
    }

    public static @Nullable TeamCacheObject getClaimingTeam(@NotNull Location location) {
        World world = location.getWorld();
        return world == null ? null : getClaimingTeam(world, location.getBlockX(), location.getBlockZ());
    }

}
