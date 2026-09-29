package xyz.jupp.minecraft.cache;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.TeamRepository;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

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
    // false if the database failed: the login is refused then, so no online player is ever without a cache entry
    public boolean preloadPlayer(@NotNull UUID uuid) {
        try {
            PlayerCache.preload(uuid);
            return true;
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().warn("Could not preload player {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    /** Blocking, in onEnable: all teams into the cache. */
    public int loadTeams() {
        return TeamCache.load();
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


    /** Blocking: adds the player to the team, only if they are in no team yet (checked in the database). */
    public boolean addPlayerToTeam(@NotNull Player player, @NotNull TeamCacheObject teamCacheObject) {
        PlayerCacheObject pco = getPlayerInCache(player);
        synchronized (pco) {
            if (pco.getTeamID() != null) return false;
            TeamRepository.TeamMember member = TeamRepository.TeamMember.of(player, "member");
            if (!TeamRepository.joinTeam(teamCacheObject.getTeamID(), member)) return false;
            teamCacheObject.memberJoined(member);
            pco.teamChanged(teamCacheObject.getTeamID());
            return true;
        }
    }

    /** An offline member was removed: a cached entry (e.g. preloaded during the login) forgets the team too. */
    public void clearTeamIfCached(@NotNull UUID uuid, @NotNull String teamID) {
        PlayerCacheObject cached = PlayerCache.getIfCached(uuid);
        if (cached != null && teamID.equals(cached.getTeamID())) cached.teamChanged(null);
    }


    public void removePlayerFromTeam(@NotNull Player target, @NotNull TeamCacheObject teamCacheObject) {
        teamCacheObject.removePlayerFromMemberList(target);
        getPlayerInCache(target).changeTeamID(null);
    }


    // blocking, called on a worker: founds the team (price included); the announcement is sent on the main thread
    public TeamRepository.Founding createNewTeam(@NotNull Player player, @NotNull String teamName, @NotNull String teamColor, int price) {
        TeamRepository.Founded founded = TeamRepository.createNewTeam(player, teamName, teamColor, price);
        if (founded.outcome() != TeamRepository.Founding.OK) return founded.outcome();
        // committed: nothing below may undo it, a failure only costs the announcement or the cache (fixed at the next login)
        try {
            TeamCache.forgetUnknownTeams();
            getPlayerInCache(player).teamChanged(founded.teamID());
            Component announcement = Text.section(Main.getChatPrefix() + "§fDas Team " + teamColor + teamName + " §fwurde von §6" + player.getName() + " §fgegründet!");
            Tasks.sync(() -> Bukkit.broadcast(announcement));
        } catch (IllegalPluginAccessException e) {
            // server stop: only the announcement is dropped
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().warn("Team {} was founded, but the cache could not follow: {}", founded.teamID(), e.toString());
        }
        return TeamRepository.Founding.OK;
    }


    public void changeAreaOptions(@NotNull TeamCacheObject teamCacheObject, @NotNull AreaOptionsEnum areaOption) {
        teamCacheObject.changeAreaSettings(areaOption);
    }


    /** Every team in the cache (all are loaded at startup). */
    public java.util.Collection<TeamCacheObject> getAllTeams() {
        return TeamCache.all();
    }

    // null if the team does not exist or its document is broken
    public @Nullable TeamCacheObject getTeamCacheObject(@Nullable String teamID) {
        return TeamCache.getTeam(teamID);
    }

}
