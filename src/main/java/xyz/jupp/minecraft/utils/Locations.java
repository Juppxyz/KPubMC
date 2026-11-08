package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The places of the map, set by admins with /ort: the spawn point, the spawn areas (protected) and the arenas (a death
 * keeps the items), each area between two block corners. Kept in memory and read from any thread; the changes block
 * (database) and run on a worker.
 */
public final class Locations {

    private Locations() {}

    public static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,16}");
    private static final int HEADROOM = 2;

    public enum Kind {
        SPAWN("spawn", "Spawnbereich"),
        ARENA("arena", "Arena");

        private final String key;
        private final String label;

        Kind(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String label() {
            return label;
        }

        private static Kind of(String key) {
            return key.equals(ARENA.key) ? ARENA : SPAWN;
        }
    }

    public record Spawn(String world, double x, double y, double z, float yaw, float pitch) {}

    /** An area; a corner is null while an admin is still setting it up. */
    public record Area(int id, Kind kind, String name, String world, @Nullable int[] corner1, @Nullable int[] corner2) {

        public boolean ready() {
            return corner1 != null && corner2 != null;
        }

        // whole blocks, both corner blocks belong to the area; a spawn area takes every height (caves and sky too), an
        // arena a block below (a corner set while flying a little) and HEADROOM above (a jump at the top stays inside)
        public boolean contains(@NotNull Location location) {
            if (corner1 == null || corner2 == null || location.getWorld() == null || !location.getWorld().getName().equals(world)) return false;
            if (!within(location.getX(), corner1[0], corner2[0], 0, 1) || !within(location.getZ(), corner1[2], corner2[2], 0, 1)) return false;
            return kind == Kind.SPAWN || within(location.getY(), corner1[1], corner2[1], 1, 1 + HEADROOM);
        }

        private static boolean within(double value, int a, int b, int below, int above) {
            return value >= Math.min(a, b) - below && value < Math.max(a, b) + above;
        }
    }

    private static volatile @Nullable Spawn spawn;
    private static volatile List<Area> areas = List.of();
    // the fallback for a spawn world that is not loaded is logged once, not on every join
    private static volatile boolean warnedSpawnWorld;


    /* reading (any thread) */

    /** At startup: reads the places and warns about what is still missing; returns the number of ready areas. */
    public static int load() {
        read();
        if (spawn == null) warn("no spawn point set, players go to the spawn of the main world; set it with /ort spawn");
        if (!anyReady(Kind.SPAWN)) warn("no spawn area is ready, the spawn is not protected; set one with /ort spawnbereich <Name> ecke1|ecke2");
        if (!anyReady(Kind.ARENA)) warn("no arena is ready, every death loses the items; set one with /ort arena <Name> ecke1|ecke2");
        return (int) areas.stream().filter(Area::ready).count();
    }

    private static void read() {
        spawn = Database.queryOne("SELECT * FROM map_spawn", row -> new Spawn(row.getString("world"),
                row.getDouble("x"), row.getDouble("y"), row.getDouble("z"), row.getFloat("yaw"), row.getFloat("pitch")));
        areas = Database.query("SELECT * FROM map_areas ORDER BY id", row -> {
            Integer x1 = row.getObject("x1", Integer.class);
            Integer x2 = row.getObject("x2", Integer.class);
            return new Area(row.getInt("id"), Kind.of(row.getString("kind")), row.getString("name"), row.getString("world"),
                    x1 == null ? null : new int[]{x1, row.getInt("y1"), row.getInt("z1")},
                    x2 == null ? null : new int[]{x2, row.getInt("y2"), row.getInt("z2")});
        });
        warnedSpawnWorld = false;
    }

    private static void warn(String message) {
        Main.getInstance().getSLF4JLogger().warn(message);
    }

    public static @Nullable Spawn spawn() {
        return spawn;
    }

    public static List<Area> all() {
        return areas;
    }

    public static @Nullable Area byName(@NotNull Kind kind, @NotNull String name) {
        for (Area area : areas) {
            if (area.kind() == kind && area.name().equalsIgnoreCase(name)) return area;
        }
        return null;
    }

    public static boolean anyReady(@NotNull Kind kind) {
        return areas.stream().anyMatch(area -> area.kind() == kind && area.ready());
    }

    private static boolean isIn(Kind kind, Location location) {
        for (Area area : areas) {
            if (area.kind() == kind && area.contains(location)) return true;
        }
        return false;
    }

    // check for all spawns
    public static boolean isLocationASpawn(@NotNull Location location) {
        return isIn(Kind.SPAWN, location);
    }

    public static boolean isInArena(@NotNull Location location) {
        return isIn(Kind.ARENA, location);
    }

    /** A new instance, so callers cannot move the shared position; without a spawn point (or its world) the spawn of the main world. */
    public static @NotNull Location getCurrentSpawn() {
        Spawn current = spawn;
        World world = current == null ? null : Bukkit.getWorld(current.world());
        if (world != null) return new Location(world, current.x(), current.y(), current.z(), current.yaw(), current.pitch());
        if (current != null && !warnedSpawnWorld) {
            warnedSpawnWorld = true;
            warn("the spawn world " + current.world() + " is not loaded, players go to the spawn of the main world");
        }
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }


    /* blocking */

    public static void setSpawn(@NotNull Location location) {
        Database.update("""
                INSERT INTO map_spawn (world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET world = EXCLUDED.world, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z,
                    yaw = EXCLUDED.yaw, pitch = EXCLUDED.pitch""",
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        Main.getInstance().getSLF4JLogger().info("spawn point at {} {} {} {}", location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
        read();
    }

    /** Sets corner 1 or 2 (the block at the location) of the area, creating it; an area moved to another world starts over. */
    public static void setCorner(@NotNull Kind kind, @NotNull String name, int corner, @NotNull Location location) {
        Area existing = byName(kind, name);
        String stored = existing == null ? name : existing.name();
        String world = location.getWorld().getName();
        Database.update("INSERT INTO map_areas (kind, name, world) VALUES (?, ?, ?) ON CONFLICT (kind, name) DO NOTHING", kind.key, stored, world);
        Database.update("""
                UPDATE map_areas SET world = ?, x1 = NULL, y1 = NULL, z1 = NULL, x2 = NULL, y2 = NULL, z2 = NULL
                WHERE kind = ? AND name = ? AND world <> ?""", world, kind.key, stored, world);
        String column = corner == 1 ? "x1 = ?, y1 = ?, z1 = ?" : "x2 = ?, y2 = ?, z2 = ?";
        Database.update("UPDATE map_areas SET " + column + " WHERE kind = ? AND name = ?",
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), kind.key, stored);
        Main.getInstance().getSLF4JLogger().info("{} {}: corner {} at {} {} {} {}", kind.key, stored, corner, world,
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        read();
    }

    public static boolean delete(@NotNull Kind kind, @NotNull String name) {
        Area area = byName(kind, name);
        if (area == null) return false;
        Database.update("DELETE FROM map_areas WHERE id = ?", area.id());
        Main.getInstance().getSLF4JLogger().info("{} {} deleted", kind.key, area.name());
        read();
        return true;
    }

}
