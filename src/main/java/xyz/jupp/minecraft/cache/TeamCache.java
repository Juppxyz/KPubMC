package xyz.jupp.minecraft.cache;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;

public class TeamCache {

    private static final HashMap<String, TeamCacheObject> teamCacheMap = new HashMap<>();

    static TeamCacheObject getTeam(@NotNull String teamID) {
        if (!teamCacheMap.containsKey(teamID)){
            try {
                TeamCacheObject teamCacheObject = new TeamCacheObject(teamID);
                teamCacheMap.put(teamID, teamCacheObject);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        return teamCacheMap.get(teamID);
    }


}
