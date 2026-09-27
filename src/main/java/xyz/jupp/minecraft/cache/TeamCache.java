package xyz.jupp.minecraft.cache;

import com.mongodb.MongoException;
import org.bson.Document;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.TeamCollection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TeamCache {

    private TeamCache() {}

    private static final ConcurrentHashMap<String, TeamCacheObject> teamCacheMap = new ConcurrentHashMap<>();
    // team ids without (valid) document, so claims of deleted/broken teams do not query the database on every event
    private static final Set<String> unknownTeamIDs = ConcurrentHashMap.newKeySet();

    static @Nullable TeamCacheObject getTeam(@Nullable String teamID) {
        if (teamID == null) return null;
        TeamCacheObject cached = teamCacheMap.get(teamID);
        if (cached != null) return cached;
        if (unknownTeamIDs.contains(teamID)) return null;

        Document document;
        try {
            document = TeamCollection.getTeamDocument(teamID);
        } catch (MongoException e) {
            // not cached, the next access tries again
            Main.getInstance().getSLF4JLogger().warn("Could not load team {}: {}", teamID, e.getMessage());
            return null;
        }
        if (document == null) {
            unknownTeamIDs.add(teamID);
            Main.getInstance().getSLF4JLogger().warn("The team with id {} doesn't exist", teamID);
            return null;
        }

        TeamCacheObject loaded;
        try {
            loaded = new TeamCacheObject(teamID, document);
        } catch (RuntimeException e) {
            unknownTeamIDs.add(teamID);
            Main.getInstance().getSLF4JLogger().warn("The team document {} is incomplete and is ignored: {}", teamID, e.toString());
            return null;
        }
        TeamCacheObject previous = teamCacheMap.putIfAbsent(teamID, loaded);
        return previous != null ? previous : loaded;
    }

    // called when a team was created
    static void forgetUnknownTeams() {
        unknownTeamIDs.clear();
    }

}
