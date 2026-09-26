package xyz.jupp.minecraft.database;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.utils.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Table 'warps' (one warp per player). Stateless, every method is blocking.
 * The stored position is exactly the one the warp cache uses.
 */
public final class WarpRepository {

    private WarpRepository() {}

    public record WarpData(UUID owner, String world, double x, double y, double z) {}

    public static List<WarpData> getAll() {
        return Database.query("SELECT uuid, world, x, y, z FROM warps", row -> new WarpData(
                row.getObject("uuid", UUID.class), row.getString("world"), row.getDouble("x"), row.getDouble("y"), row.getDouble("z")));
    }

    /* creates the warp only if the player has none yet */
    public static void createNewPlayerWarp(@NotNull Player player) {
        Location loc = player.getLocation();
        int created = Database.update("INSERT INTO warps (uuid, world, x, y, z) VALUES (?, ?, ?, ?, ?) ON CONFLICT (uuid) DO NOTHING",
                player.getUniqueId(), loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        if (created == 0) return;
        Logger.console("created player warp for %s on %d,%d,%d (%s)".formatted(player.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getWorld().getName()));
    }

    public static void updatePlayerWarp(@NotNull Player player) {
        Location loc = player.getLocation();
        int updated = Database.update("UPDATE warps SET world = ?, x = ?, y = ?, z = ? WHERE uuid = ?",
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), player.getUniqueId());
        if (updated == 0) return;
        Logger.console("updated player warp for %s on %d,%d,%d (%s)".formatted(player.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getWorld().getName()));
    }

    public static void removePlayerWarp(@NotNull Player player) {
        Database.update("DELETE FROM warps WHERE uuid = ?", player.getUniqueId());
        Logger.console("deleted player warp for %s".formatted(player.getName()));
    }

}
