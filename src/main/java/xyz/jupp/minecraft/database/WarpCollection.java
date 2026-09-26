package xyz.jupp.minecraft.database;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.utils.Logger;

import static com.mongodb.client.model.Filters.eq;

/**
 * DAO for the collection 'warps'. Stateless, every method is blocking.
 */
public final class WarpCollection {

    private WarpCollection() {}

    private static MongoCollection<Document> warps() {
        return MongoDB.getInstance().getKpubMC().getCollection("warps");
    }

    public static FindIterable<Document> getAllWarps() {
        return warps().find();
    }

    /* creates the warp only if the player has none yet */
    public static void createNewPlayerWarp(@NotNull Player player) {
        Location loc = player.getLocation();
        Document warpDocument = new Document("world", loc.getWorld().getName());
        warpDocument.append("x", loc.getX());
        warpDocument.append("y", loc.getY() + 0.5D);
        warpDocument.append("z", loc.getZ());
        warpDocument.append("isTeam", false);
        boolean created = warps().updateOne(eq("uuid", player.getUniqueId().toString()),
                new Document("$setOnInsert", warpDocument), new UpdateOptions().upsert(true)).getUpsertedId() != null;
        if (!created) return;
        Logger.console("created player warp for %s on %d,%d,%d (%s)".formatted(player.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getWorld().getName()));
    }

    public static void updatePlayerWarp(@NotNull Player player) {
        Location location = player.getLocation();

        // Erstellen eines Dokuments mit den zu aktualisierenden Feldern
        Document updatedFields = new Document("x", location.getBlockX() + 0.5D);
        updatedFields.append("y", location.getBlockY() + 0.5D);
        updatedFields.append("z", location.getBlockZ() + 0.5D);
        updatedFields.append("world", location.getWorld().getName());
        boolean updated = warps().updateOne(eq("uuid", player.getUniqueId().toString()), new Document("$set", updatedFields)).getMatchedCount() > 0;
        if (!updated) return;

        Logger.console("updated player warp for %s on %d,%d,%d (%s)".formatted(
                player.getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                location.getWorld().getName()
        ));
    }

    public static void removePlayerWarp(@NotNull Player player) {
        warps().deleteOne(eq("uuid", player.getUniqueId().toString()));
        Logger.console("deleted player warp for %s".formatted(player.getName()));
    }

}
