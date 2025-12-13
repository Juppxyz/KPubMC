package xyz.jupp.minecraft.database;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;


public class ChunkCollection {

    private static final MongoCollection<Document> chunkCollection = MongoDB.getInstance().getKpubMC().getCollection("chunks");

    private String teamID = null;
    private String chunkID = null;

    public ChunkCollection(@NotNull String teamID, @NotNull String chunkID) {
        this.teamID = teamID;
        this.chunkID = chunkID;
    }

    public void createChunkInDatabase(@NotNull String worldName, int x, int z) {
        Document doc = new Document("teamID", teamID);
        doc.append("x", x);
        doc.append("z", z);
        doc.append("worldName", worldName);
        doc.append("chunkID", this.chunkID);
        chunkCollection.insertOne(doc);
        Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "");
    }

    public void removeChunkInDatabase(@NotNull String teamID) {
        Bson filter = Filters.and(
                Filters.eq("chunkID", this.chunkID),
                Filters.eq("teamID", teamID)
        );
        chunkCollection.deleteOne(filter);
    }

    public static FindIterable<Document> getAllChunksFromDatabase() {
        FindIterable<Document> iterDoc = chunkCollection.find();
        return iterDoc;
    }

}
