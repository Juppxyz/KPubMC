package xyz.jupp.minecraft.cache;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.UUID;

/**
 * This is the central wrapper class which handles all access for the player and team cache.
 * The caches are thread-safe and write-through: every change is written to the database immediately.
 * Chunk claims and warps have their own caches (ChunkCache, WarpCache).
 * */

public class CacheHandler {
    private CacheHandler(){}

    // The single pattern is the only way to access the cache.
    private static final CacheHandler instance = new CacheHandler();
    public static CacheHandler getInstance() {
        return instance;
    }


    // Never null: falls back to a synchronous database load if the player was not preloaded.
    public PlayerCacheObject getPlayerInCache(@NotNull Player player) {
        return PlayerCache.getPlayer(player);
    }


    // Blocking, meant for the AsyncPlayerPreLoginEvent so the join does not hit the database.
    public void preloadPlayer(@NotNull UUID uuid) {
        try {
            PlayerCache.preload(uuid);
        } catch (RuntimeException e) {
            // getPlayerInCache loads the player on first access instead
            Main.getInstance().getSLF4JLogger().warn("Could not preload player {}: {}", uuid, e.getMessage());
        }
    }


    public void removePlayerFromCache(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        PlayerCache.removePlayer(uuid);
        // an async task running during the quit may have cached the player again
        if (Main.getInstance().isEnabled()) {
            Tasks.syncLater(1L, () -> {
                if (Bukkit.getPlayer(uuid) == null) PlayerCache.removePlayer(uuid);
            });
        }
    }


    public String changeTeamMemberRole(@NotNull PlayerCacheObject playerCacheObject) {
        return playerCacheObject.getTeamCacheObject().changePlayerTeamRole(playerCacheObject.getUuid());
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
        String teamID = TeamCollection.createNewTeam(player, teamName, teamColor);
        TeamCache.forgetUnknownTeams();
        Bukkit.broadcastMessage(Main.getChatPrefix() + "§fDas Team " + teamColor + teamName + " §fwurde von §6" + player.getName() + " §fgegründet!");
        getPlayerInCache(player).changeTeamID(teamID);
    }


    public void changeAreaOptions(@NotNull TeamCacheObject teamCacheObject, @NotNull AreaOptionsEnum areaOption) {
        teamCacheObject.changeAreaSettings(areaOption);
    }


    // null if the team does not exist or its document is broken
    public @Nullable TeamCacheObject getTeamCacheObject(@Nullable String teamID) {
        return TeamCache.getTeam(teamID);
    }

}
