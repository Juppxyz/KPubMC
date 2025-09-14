package xyz.jupp.minecraft.database;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Date;

public class CommandLogCollection {

    private final MongoCollection<Document> commandLog = MongoDB.getInstance().getKpubMC().getCollection("cmdLog");


    private Player player;
    private String cmd;

    public CommandLogCollection(@NotNull Player player, @NotNull String cmd) {
        this.player = player;
        this.cmd = cmd;
    }

    public void addNewEntry() {
        Document doc = new Document("playerName", getPlayer().getName());
        doc.append("playerUUID", getPlayer().getUniqueId().toString());
        doc.append("cmd", cmd);
        doc.append("timestamp", Date.from(Instant.now()));
        commandLog.insertOne(doc);
    }

    private Player getPlayer() {
        return player;
    }
}
