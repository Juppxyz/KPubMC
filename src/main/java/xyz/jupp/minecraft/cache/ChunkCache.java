package xyz.jupp.minecraft.cache;

import com.mongodb.client.FindIterable;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.database.ChunkCollection;

import java.util.HashMap;

public class ChunkCache {

    private final static ChunkCache instance = new ChunkCache();
    public static ChunkCache getInstance() {
        return instance;
    }

    private HashMap<String, ChunkCacheObject> chunkCache = null;

    public HashMap<String, ChunkCacheObject> getChunkCache() {
        if (chunkCache == null) {
            chunkCache = new HashMap<>();
            FindIterable<Document> iterable = ChunkCollection.getAllChunksFromDatabase();
            for (Document document : iterable) {
                String worldName = document.getString("worldName");
                String teamID = document.getString("teamID");
                int x = document.getInteger("x");
                int z = document.getInteger("z");
                String chunkID = genChunkID(worldName, x, z);
                ChunkCacheObject chunkCacheObject = new ChunkCacheObject(teamID, worldName, x , z, chunkID);
                chunkCache.put(chunkID, chunkCacheObject);
            }
        }
        return chunkCache;
    }

    private String genChunkID(String worldName, int x, int z) {
        return worldName + ":" + x + ":" + z;
    }

    public ChunkCacheObject getChunkObject(@NotNull String worldName, int x, int z) {
        String chunkID = genChunkID(worldName, x, z);
        if (getChunkCache().containsKey(chunkID)) {
            return getChunkCache().get(chunkID);
        }
        return null;
    }

    public boolean addChunk(@NotNull String teamID, @NotNull String worldName, int x, int z) {
        String chunkID = genChunkID(worldName, x, z);
        if (getChunkCache().containsKey(chunkID)) {
            return false;
        }
        ChunkCacheObject chunkCacheObject = new ChunkCacheObject(teamID, worldName, x , z, chunkID);
        chunkCache.put(chunkCacheObject.getChunkID(), chunkCacheObject);
        ChunkCollection chunkCollection = new ChunkCollection(teamID, chunkCacheObject.getChunkID());
        chunkCollection.createChunkInDatabase(worldName, x, z);
        return true;
    }

}
