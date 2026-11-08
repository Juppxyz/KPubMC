package xyz.jupp.minecraft.jail;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The jail cells: each an area between two block corners and a spawn, set by admins with /bestrafung zelle. A new
 * prisoner gets the ready cell with the fewest prisoners; inside any cell a prisoner counts as in jail. The cells are
 * kept in memory and read from any thread; the changes block (database) and run on a worker.
 */
public final class JailCells {

    private JailCells() {}

    public static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,16}");
    private static final int HEADROOM = 2;

    /** A cell; corners and spawn are null while an admin is still setting it up. */
    public record Cell(int id, String name, String world, @Nullable int[] corner1, @Nullable int[] corner2,
                       @Nullable double[] spawn, float yaw, float pitch) {

        // a spawn outside the area would count every prisoner as escaped right away
        public boolean ready() {
            BoundingBox area = area();
            return area != null && spawn != null && area.contains(spawn[0], spawn[1], spawn[2]);
        }

        // whole blocks: both corner blocks belong to the cell, plus HEADROOM above (corners set standing, a jump is no
        // escape) and a block below (a corner set while flying a little)
        private @Nullable BoundingBox area() {
            if (corner1 == null || corner2 == null) return null;
            return new BoundingBox(Math.min(corner1[0], corner2[0]), Math.min(corner1[1], corner2[1]) - 1, Math.min(corner1[2], corner2[2]),
                    Math.max(corner1[0], corner2[0]) + 1, Math.max(corner1[1], corner2[1]) + 1 + HEADROOM, Math.max(corner1[2], corner2[2]) + 1);
        }

        public boolean contains(@NotNull Location location) {
            BoundingBox area = area();
            return area != null && location.getWorld() != null && location.getWorld().getName().equals(world)
                    && area.contains(location.getX(), location.getY(), location.getZ());
        }

        /** Null if the spawn is not set or its world is not loaded. */
        public @Nullable Location spawnLocation() {
            World loaded = Bukkit.getWorld(world);
            if (spawn == null || loaded == null) return null;
            return new Location(loaded, spawn[0], spawn[1], spawn[2], yaw, pitch);
        }
    }

    private static volatile List<Cell> cells = List.of();


    /* reading (any thread) */

    public static int load() {
        cells = Database.query("SELECT * FROM jail_cells ORDER BY id", row -> {
            Integer x1 = row.getObject("x1", Integer.class);
            Integer x2 = row.getObject("x2", Integer.class);
            Double spawnX = row.getObject("spawn_x", Double.class);
            return new Cell(row.getInt("id"), row.getString("name"), row.getString("world"),
                    x1 == null ? null : new int[]{x1, row.getInt("y1"), row.getInt("z1")},
                    x2 == null ? null : new int[]{x2, row.getInt("y2"), row.getInt("z2")},
                    spawnX == null ? null : new double[]{spawnX, row.getDouble("spawn_y"), row.getDouble("spawn_z")},
                    row.getFloat("spawn_yaw"), row.getFloat("spawn_pitch"));
        });
        long ready = cells.stream().filter(Cell::ready).count();
        if (ready == 0) Main.getInstance().getSLF4JLogger().warn("no jail cell is ready, set one with /bestrafung zelle <Name> ecke1|ecke2|spawn");
        return (int) ready;
    }

    public static List<Cell> all() {
        return cells;
    }

    public static @Nullable Cell get(@Nullable Integer id) {
        if (id == null) return null;
        for (Cell cell : cells) {
            if (cell.id() == id) return cell;
        }
        return null;
    }

    public static @Nullable Cell byName(@NotNull String name) {
        for (Cell cell : cells) {
            if (cell.name().equalsIgnoreCase(name)) return cell;
        }
        return null;
    }

    public static boolean anyReady() {
        return cells.stream().anyMatch(Cell::ready);
    }

    /** Inside any ready cell. */
    public static boolean contains(@NotNull Location location) {
        for (Cell cell : cells) {
            if (cell.ready() && cell.contains(location)) return true;
        }
        return false;
    }

    /** Where a prisoner is put: their cell's spawn, without one the first ready cell's; null if there is no cell. */
    public static @Nullable Location spawnFor(@Nullable Integer cellID) {
        Cell own = get(cellID);
        if (own != null && own.ready()) return own.spawnLocation();
        for (Cell cell : cells) {
            if (cell.ready()) return cell.spawnLocation();
        }
        return null;
    }


    /* blocking */

    /** Prisoners per cell id. */
    public static Map<Integer, Integer> occupancy() {
        Map<Integer, Integer> counts = new HashMap<>();
        Database.query("SELECT jail_cell, COUNT(*) FROM players WHERE jail AND jail_cell IS NOT NULL GROUP BY jail_cell", row -> {
            counts.put(row.getInt(1), row.getInt(2));
            return null;
        });
        return counts;
    }

    /** The ready cell with the fewest prisoners (the older one on a tie), null if no cell is ready. */
    public static @Nullable Integer pickCell() {
        List<Cell> ready = cells.stream().filter(Cell::ready).toList();
        if (ready.isEmpty()) return null;
        Map<Integer, Integer> counts = occupancy();
        return ready.stream().min(Comparator.comparingInt((Cell cell) -> counts.getOrDefault(cell.id(), 0)).thenComparingInt(Cell::id))
                .map(Cell::id).orElse(null);
    }

    /** Sets corner 1 or 2 (the block at the location) of the cell, creating it; a cell moved to another world starts over. */
    public static void setCorner(@NotNull String name, int corner, @NotNull Location location) {
        String column = corner == 1 ? "x1 = ?, y1 = ?, z1 = ?" : "x2 = ?, y2 = ?, z2 = ?";
        String stored = prepare(name, location);
        Database.update("UPDATE jail_cells SET " + column + " WHERE name = ?",
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), stored);
        Main.getInstance().getSLF4JLogger().info("jail cell {}: corner {} at {} {} {}", stored, corner,
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        load();
    }

    public static void setSpawn(@NotNull String name, @NotNull Location location) {
        String stored = prepare(name, location);
        Database.update("UPDATE jail_cells SET spawn_x = ?, spawn_y = ?, spawn_z = ?, spawn_yaw = ?, spawn_pitch = ? WHERE name = ?",
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), stored);
        Main.getInstance().getSLF4JLogger().info("jail cell {}: spawn at {} {} {}", stored, location.getX(), location.getY(), location.getZ());
        load();
    }

    // the cell under its stored name (a new one under the given name), in the location's world
    private static String prepare(String name, Location location) {
        Cell existing = byName(name);
        String stored = existing == null ? name : existing.name();
        String world = location.getWorld().getName();
        Database.update("INSERT INTO jail_cells (name, world) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", stored, world);
        Database.update("""
                UPDATE jail_cells SET world = ?, x1 = NULL, y1 = NULL, z1 = NULL, x2 = NULL, y2 = NULL, z2 = NULL,
                    spawn_x = NULL, spawn_y = NULL, spawn_z = NULL, spawn_yaw = NULL, spawn_pitch = NULL
                WHERE name = ? AND world <> ?""", world, stored, world);
        return stored;
    }

    public enum Removal { OK, UNKNOWN, OCCUPIED }

    /** Deletes an empty cell; a cell with prisoners stays. */
    public static Removal delete(@NotNull String name) {
        Cell cell = byName(name);
        if (cell == null) return Removal.UNKNOWN;
        int deleted = Database.update("DELETE FROM jail_cells WHERE id = ? AND NOT EXISTS (SELECT 1 FROM players WHERE jail AND jail_cell = ?)",
                cell.id(), cell.id());
        if (deleted == 0) return Removal.OCCUPIED;
        Main.getInstance().getSLF4JLogger().info("jail cell {} deleted", cell.name());
        load();
        return Removal.OK;
    }

}
