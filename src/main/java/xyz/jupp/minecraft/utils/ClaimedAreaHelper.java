package xyz.jupp.minecraft.utils;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.ChunkCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.team.Relations;
import xyz.jupp.minecraft.team.Teams;

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

    /** Main thread: the team whose area this is, unless it is the player's own or a partner's; null in the wilderness. */
    public static @Nullable TeamCacheObject foreignTeam(@NotNull Location location, @NotNull Player player) {
        TeamCacheObject team = getClaimingTeam(location);
        if (team == null) return null;
        String own = CacheHandler.getInstance().getPlayerInCache(player).getTeamID();
        return team.getTeamID().equals(own) || Relations.partners(team.getTeamID(), own) ? null : team;
    }

    /** Strangers may use nothing there: "Fremde Interaktionen" off, a perk from INTERACTION_LEVEL. */
    public static boolean isLocked(@NotNull TeamCacheObject team) {
        return team.getLevel() >= Teams.INTERACTION_LEVEL && !team.isZoneOptionInteract();
    }

}
