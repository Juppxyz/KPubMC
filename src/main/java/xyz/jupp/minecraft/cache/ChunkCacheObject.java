package xyz.jupp.minecraft.cache;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.jetbrains.annotations.NotNull;

public class ChunkCacheObject {

    private String teamID = null;
    private String chunkID = null;
    private String worldName = null;
    private int x = 0;
    private int z = 0;

    public ChunkCacheObject(@NotNull String teamID, @NotNull String worldName, int x, int z, @NotNull String chunkID) {
        this.teamID = teamID;
        this.worldName = worldName;
        this.x = x;
        this.z = z;
        this.chunkID = chunkID;
    }


    public String getChunkID() {
        return chunkID;
    }

    public Chunk getChunk() {
        return Bukkit.getWorld(this.worldName).getChunkAt(this.x, this.z);
    }

    public String getWorldName() {
        return worldName;
    }

    public String getTeamID() {
        return teamID;
    }





}
