package xyz.jupp.minecraft.database;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

import static com.mongodb.client.model.Filters.eq;

public class TeamBlockCollection {

    private final MongoCollection<Document> teamBlocksCollection = MongoDB.getInstance().getKpubMC().getCollection("teamBlocks");
    private String teamID;

    public TeamBlockCollection(@NotNull String teamID) {
        this.teamID = teamID;
    }


    public void createNewTeamBlock() {
        if (!existTeamBlockDocument()) {
            Document document = new Document("teamID", getTeamID());
            document.append("teamID", getTeamID());
            document.append("x", 0.0D);
            document.append("y", 0.0D);
            document.append("z", 0.0D);
            document.append("isActive", true);
            getTeamBlocksCollection().insertOne(document);
            Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "created new teamblock for " + getTeamID());
            return;
        }
    }


    public void updateTeamBlockLocation(double x, double y, double z) {
        Bson filter = eq("teamID", getTeamID());
        Document newData = new Document();
        newData.append("x", x);
        newData.append("y", y);
        newData.append("z", z);
        newData.append("isActive", true);
        Document updatedDocument = new Document("$set", newData);
        getTeamBlocksCollection().updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "update teamblock for " + getTeamID());
    }

    public boolean existTeamBlockDocument() {
        Bson filter = eq("teamID", getTeamID());
        return getTeamBlocksCollection().find(filter).first() != null;
    }

    public void setActiveState(boolean isActive) {
        Bson filter = eq("teamID", getTeamID());
        getTeamBlocksCollection().updateOne(filter, new Document("$set", new Document("isActive", isActive)));
    }


    public Document getTeamBlockData() {
        Bson filter = eq("teamID", getTeamID());
        return getTeamBlocksCollection().find(filter).first();
    }

    private MongoCollection<Document> getTeamBlocksCollection() {
        return teamBlocksCollection;
    }

    private String getTeamID() {
        return teamID;
    }


}
