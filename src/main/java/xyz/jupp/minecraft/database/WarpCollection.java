package xyz.jupp.minecraft.database;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.utils.Logger;

import static com.mongodb.client.model.Filters.eq;

public class WarpCollection {

    private static final MongoCollection<Document> warpsCollection = MongoDB.getInstance().getKpubMC().getCollection("warps");
    private Player player;

    public WarpCollection(@NotNull Player player) {this.player = player;}

    public static FindIterable<Document> getAllWarps() {
        FindIterable<Document> iterDoc = warpsCollection.find();
        return iterDoc;
    }

    public void createNewPlayerWarp() {
        if (existPlayerWarp()) return;
        Location loc = player.getLocation();
        Document warpDocument = new Document("uuid", player.getUniqueId().toString());
        warpDocument.append("world", loc.getWorld().getName());
        warpDocument.append("x", loc.getX());
        warpDocument.append("y", loc.getY() + 0.5D);
        warpDocument.append("z", loc.getZ());
        warpDocument.append("isTeam", false);
        warpsCollection.insertOne(warpDocument);
        Logger.console("created player warp for %s on %d,%d,%d (%s)".formatted(player.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getWorld().getName()));
    }

    public void updatePlayerWarp() {
        if (!existPlayerWarp()) return;
        Location location = player.getLocation();

        // Erstellen eines Dokuments mit den zu aktualisierenden Feldern
        Document updatedFields = new Document("x", player.getLocation().getBlockX());
        updatedFields.append("y", player.getLocation().getBlockY() + 0.5D);
        updatedFields.append("z", player.getLocation().getBlockZ());
        updatedFields.append("world", player.getLocation().getWorld().getName());
        Document updateOperation = new Document("$set", updatedFields);
        warpsCollection.findOneAndUpdate(eq("uuid", player.getUniqueId().toString()), updateOperation);

        Logger.console("updated player warp for %s on %d,%d,%d (%s)".formatted(
                player.getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                location.getWorld().getName()
        ));
    }


    public void createNewTeamWarp(String teamID) {
        if (!existPlayerWarp()) return;
        Location loc = player.getLocation();
        Document warpDocument = new Document("uuid", teamID);
        warpDocument.append("world", loc.getWorld().getName());
        warpDocument.append("x", loc.getX());
        warpDocument.append("y", loc.getY() );
        warpDocument.append("z", loc.getZ());
        warpDocument.append("isTeam", true);
    }


    public void removePlayerWarp() {
        warpsCollection.findOneAndDelete(eq("uuid", player.getUniqueId().toString()));
        Logger.console("deleted player warp for %s".formatted(player.getName()));
    }

    private boolean existPlayerWarp() {
        Bson filter = eq("uuid", getPlayer().getUniqueId().toString());
        return (warpsCollection.find(filter).first() != null);
    }

    // Getter
    private Player getPlayer() {
        return player;
    }
}
