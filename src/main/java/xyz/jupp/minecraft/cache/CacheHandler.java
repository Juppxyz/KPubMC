package xyz.jupp.minecraft.cache;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.database.TeamCollection;

/**
 * This is the central wrapper class which handles all access for the local cache memory.
 *
 * Local storage is currently used:
 * -> Player data
 * */

public class CacheHandler {
    private CacheHandler(){}

    // The single pattern is the only way to access the cache.
    private static CacheHandler instance = null;
    public static CacheHandler getInstance() {
        return instance == null ? instance = new CacheHandler() : instance;
    }


    // Contains the cache for the player data.
    private final PlayerCache playerCache = new PlayerCache();
    public PlayerCacheObject getPlayerInCache(@NotNull Player player) {
        return playerCache.getPlayer(player);
    }

    public void removePlayerFromCache(@NotNull Player player) {
        playerCache.removePlayer(player);
    }

    public String changeTeamMemberRole(@NotNull PlayerCacheObject playerCacheObject) {
        return playerCacheObject.getTeamCacheObject().changePlayerTeamRole(playerCacheObject.getPlayer());
    }


    public void addPlayerToTeam(@NotNull Player player, @NotNull TeamCacheObject teamCacheObject) {
        teamCacheObject.addPlayerToMemberList(player);
        getPlayerInCache(player).changeTeamID(teamCacheObject.getTeamID());
    }


    public void removePlayerFromTeam(@NotNull Player target, @NotNull TeamCacheObject teamCacheObject) {
        teamCacheObject.removePlayerFromMemberList(target);
        getPlayerInCache(target).changeTeamID(null);
    }


    public void createNewTeam(@NotNull Player player, @NotNull String teamName, @NotNull String teamColor) {
        TeamCollection teamCollection = new TeamCollection("");
        String teamID = teamCollection.createNewTeam(player, teamName, teamColor);
        getPlayerInCache(player).changeTeamID(teamID);
        playerCache.reinitialisePlayerObject(player);
    }


    public void incAlreadyPurchased(@NotNull PlayerCacheObject playerCacheObject) {
        TeamCollection teamCollection = new TeamCollection(playerCacheObject.getTeamID());
        teamCollection.incTeamBlockAlreadyPurchased();
        playerCacheObject.getTeamCacheObject().incTeamBlockAlreadyPurchased();
    }

    public TeamCacheObject getTeamCacheObject(@NotNull String teamID) {
        return TeamCache.getTeam(teamID);
    }


}
