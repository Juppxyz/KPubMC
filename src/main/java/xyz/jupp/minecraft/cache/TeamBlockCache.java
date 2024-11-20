package xyz.jupp.minecraft.cache;

import org.bson.Document;
import xyz.jupp.minecraft.database.TeamBlockCollection;

import java.util.HashMap;

public class TeamBlockCache {

    private final static HashMap<String, TeamBlockCacheObject> teamBlockCache = new HashMap<>(12);

    public static TeamBlockCacheObject getTeamBlock(String teamID) {
        if (!teamBlockCache.containsKey(teamID)) {
            TeamBlockCollection teamBlockCollection = new TeamBlockCollection(teamID);
            Document teamBlockData = teamBlockCollection.getTeamBlockData();
            if (teamBlockData == null) {
                return null;
            }
            TeamBlockCacheObject teamBlockCacheObject = new TeamBlockCacheObject(teamID,
                    teamBlockData.getDouble("x"),
                    teamBlockData.getDouble("y"),
                    teamBlockData.getDouble("z"),
                    teamBlockData.getBoolean("isActive")
            );
            teamBlockCache.put(teamID, teamBlockCacheObject);
            return teamBlockCacheObject;
        }
        return teamBlockCache.get(teamID);
    }

    public static HashMap<String, TeamBlockCacheObject> getTeamBlockCache() {
        return teamBlockCache;
    }
}
