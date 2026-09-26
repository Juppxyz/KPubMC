package xyz.jupp.minecraft.cache;

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

    static @Nullable TeamCacheObject getTeam(@Nullable String teamID) {
        if (teamID == null) return null;
        TeamCacheObject cached = teamCacheMap.get(teamID);
        if (cached != null) return cached;
        if (unknownTeamIDs.contains(teamID)) return null;

        TeamData data;
        try {
            data = TeamRepository.getTeam(teamID);
        } catch (DatabaseException e) {
            // not cached, the next access tries again
            Main.getInstance().getSLF4JLogger().warn("Could not load team {}: {}", teamID, e.getMessage());
            return null;
        }
        if (data == null) {
            unknownTeamIDs.add(teamID);
            Main.getInstance().getSLF4JLogger().warn("The team with id {} doesn't exist", teamID);
            return null;
        }

        TeamCacheObject loaded = new TeamCacheObject(data);
        TeamCacheObject previous = teamCacheMap.putIfAbsent(teamID, loaded);
        return previous != null ? previous : loaded;
    }

    // called when a team was created
    static void forgetUnknownTeams() {
        unknownTeamIDs.clear();
    }

}
