package xyz.jupp.minecraft.cache;

import org.jetbrains.annotations.NotNull;

public class ChunkCacheObject {

    private final String teamID;

    ChunkCacheObject(@NotNull String teamID) {
        this.teamID = teamID;
    }

    public String getTeamID() {
        return teamID;
    }

}
