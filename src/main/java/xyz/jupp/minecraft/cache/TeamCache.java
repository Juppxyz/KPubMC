package xyz.jupp.minecraft.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.DatabaseException;
import xyz.jupp.minecraft.database.TeamRepository;
import xyz.jupp.minecraft.database.TeamRepository.TeamData;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TeamCache {

    private TeamCache() {}

    private static final ConcurrentHashMap<String, TeamCacheObject> teamCacheMap = new ConcurrentHashMap<>();
    // team ids without a row, so claims of deleted/broken teams do not query the database on every event
    private static final Set<String> unknownTeamIDs = ConcurrentHashMap.newKeySet();
    // a failed load is not retried for a while: during a database outage claim checks would wait 5 s each
    private static final long RETRY_MILLIS = 30_000;
    private static final ConcurrentHashMap<String, Long> failedUntil = new ConcurrentHashMap<>();

    /** Blocking, in onEnable: every team, so the claim checks on the main thread never query the database. */
    static int load() {
        int count = 0;
        for (TeamData data : TeamRepository.loadAllTeams()) {
            teamCacheMap.put(data.teamID(), new TeamCacheObject(data));
            count++;
        }
        return count;
    }

    static @Nullable TeamCacheObject getTeam(@Nullable String teamID) {
        if (teamID == null) return null;
        TeamCacheObject cached = teamCacheMap.get(teamID);
        if (cached != null) return cached;
        if (unknownTeamIDs.contains(teamID)) return null;
        Long until = failedUntil.get(teamID);
        if (until != null && until > System.currentTimeMillis()) return null;

        TeamData data;
        try {
            data = TeamRepository.getTeam(teamID);
        } catch (DatabaseException e) {
            // not cached, tried again after a while
            failedUntil.put(teamID, System.currentTimeMillis() + RETRY_MILLIS);
            Main.getInstance().getSLF4JLogger().warn("Could not load team {}: {}", teamID, e.getMessage());
            return null;
        }
        failedUntil.remove(teamID);
        if (data == null) {
            unknownTeamIDs.add(teamID);
            Main.getInstance().getSLF4JLogger().warn("The team with id {} doesn't exist", teamID);
            return null;
        }

        TeamCacheObject loaded = new TeamCacheObject(data);
        TeamCacheObject previous = teamCacheMap.putIfAbsent(teamID, loaded);
        return previous != null ? previous : loaded;
    }

    static java.util.Collection<TeamCacheObject> all() {
        return java.util.List.copyOf(teamCacheMap.values());
    }

    // a dissolved team: gone from the cache, and never loaded again
    static void forget(@NotNull String teamID) {
        teamCacheMap.remove(teamID);
        unknownTeamIDs.add(teamID);
    }

    // called when a team was created
    static void forgetUnknownTeams() {
        unknownTeamIDs.clear();
    }

}
