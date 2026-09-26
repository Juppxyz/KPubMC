package xyz.jupp.minecraft.database;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

/**
 * DAO for the collection 'chunks' (claimed team chunks). Stateless, every method is blocking.
 */
public final class ChunkCollection {

    private ChunkCollection() {}

    private static MongoCollection<Document> chunks() {
        return MongoDB.getInstance().getKpubMC().getCollection("chunks");
    }

    public static void createChunkInDatabase(@NotNull String teamID, @NotNull String chunkID, @NotNull String worldName, int x, int z) {
        Document doc = new Document("teamID", teamID);
        doc.append("x", x);
        doc.append("z", z);
        doc.append("worldName", worldName);
        doc.append("chunkID", chunkID);
        chunks().insertOne(doc);
        Main.getInstance().getSLF4JLogger().info("claimed chunk {} for team {}", chunkID, teamID);
    }

    public static void removeChunkInDatabase(@NotNull String teamID, @NotNull String chunkID) {
        chunks().deleteOne(Filters.and(
                Filters.eq("chunkID", chunkID),
                Filters.eq("teamID", teamID)
        ));
        Main.getInstance().getSLF4JLogger().info("released chunk {} of team {}", chunkID, teamID);
    }

    public static FindIterable<Document> getAllChunksFromDatabase() {
        return chunks().find();
    }

}
