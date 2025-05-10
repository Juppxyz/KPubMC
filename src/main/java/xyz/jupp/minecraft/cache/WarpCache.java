package xyz.jupp.minecraft.cache;

import com.mongodb.client.FindIterable;
import org.bson.Document;
import org.bukkit.entity.Player;
import org.checkerframework.checker.units.qual.N;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.database.WarpCollection;
import java.util.HashMap;

public class WarpCache {

    // The Warp Cache is standalone and not integrated in the main CacheHandler!!!

    private final static WarpCache instance = new WarpCache();
    public static WarpCache getInstance() {
        return instance;
    }

    private HashMap<String, WarpCacheObject> warpCache = null;
    public HashMap<String, WarpCacheObject> getWarpCache() {
        if (warpCache == null) {
            warpCache = new HashMap<>();
            FindIterable<Document> iterable = WarpCollection.getAllWarps();
            for (Document document : iterable) {
                System.out.println(document.toJson());
                WarpCacheObject warpCacheObject = new WarpCacheObject(
                        document.getDouble("x"),
                        document.getDouble("y"),
                        document.getDouble("z"),
                        document.getString("world")
                );
                warpCache.put(document.getString("uuid"), warpCacheObject);
            }
        }
        return warpCache;
    }

    public void addNewPlayerWarp(@NotNull Player player) {
        WarpCacheObject warpCacheObject = new WarpCacheObject(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ(), player.getWorld().getName());
        if (!getWarpCache().containsKey(player.getUniqueId().toString())) {
            WarpCollection warpCollection = new WarpCollection(player);
            warpCollection.createNewPlayerWarp();
            getWarpCache().put(player.getUniqueId().toString(), warpCacheObject);
        }
    }

    public void removePlayerWarp(@NotNull Player player) {
        if (getWarpCache().containsKey(player.getUniqueId().toString())) {
            WarpCollection warpCollection = new WarpCollection(player);
            warpCollection.removePlayerWarp();
            getWarpCache().remove(player.getUniqueId().toString());
        }
    }

    public void updatePlayerWarp(@NotNull Player player) {
        if (getWarpCache().containsKey(player.getUniqueId().toString())) {
            WarpCollection warpCollection = new WarpCollection(player);
            warpCollection.updatePlayerWarp();

            WarpCacheObject warpCacheObject = new WarpCacheObject(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ(), player.getWorld().getName());
            getWarpCache().put(player.getUniqueId().toString(), warpCacheObject);
        }
    }

}
