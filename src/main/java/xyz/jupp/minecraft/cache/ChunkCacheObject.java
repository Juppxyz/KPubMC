package xyz.jupp.minecraft.cache;

import org.jetbrains.annotations.NotNull;

public class ChunkCacheObject {

    private final String teamID;
    private final String chunkID;

    ChunkCacheObject(@NotNull String teamID, @NotNull String chunkID) {
        this.teamID = teamID;
        this.chunkID = chunkID;
    }


    public String getChunkID() {
        return chunkID;
    }

    public String getTeamID() {
        return teamID;
    }

}
