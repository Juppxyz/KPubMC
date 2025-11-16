package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;

public class Locations {

    private static final String worldName = "world_MCWinter";

    private static final Location SPAWN_2024_LOCATION = new Location(Bukkit.getWorld(worldName), 92624, 72, 114430);
    private static final double SPAWN_2024_MIN_X = 92508.0D;
    private static final double SPAWN_2024_MAX_X = 92810.0D;
    private static final double SPAWN_2024_MIN_Z = 114375.0D;
    private static final double SPAWN_2024_MAX_Z = 114626.0D;
    private static final double SPAWN_2024_Y = -64.0D;

    private static final Location SPAWN_2025_LOCATION = new Location(Bukkit.getWorld(worldName), 150069, 240, 150293);
    private static final double SPAWN_2025_MAX_X = 150200.0D;
    private static final double SPAWN_2025_MAX_Z = 150348.0D;
    private static final double SPAWN_2025_MIN_X = 149966.0D;
    private static final double SPAWN_2025_MIN_Z = 150200.0D;
    private static final double SPAWN_2025_Y = -64.0D;


    // Spawn - 2024
    public static boolean isLocation2024SpawnArea(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(worldName)) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        boolean checkX = x >= SPAWN_2024_MIN_X && x <= SPAWN_2024_MAX_X;
        boolean checkY = y >= SPAWN_2024_Y;
        boolean checkZ = z >= SPAWN_2024_MIN_Z && z <= SPAWN_2024_MAX_Z;
        return checkX && checkY && checkZ;
    }


    // Spawn - 2025
    public static boolean isLocation2025SpawnArea(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(worldName)) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        boolean checkX = x >= SPAWN_2025_MIN_X && x <= SPAWN_2025_MAX_X;
        boolean checkY = y >= SPAWN_2025_Y;
        boolean checkZ = z >= SPAWN_2025_MIN_Z && z <= SPAWN_2025_MAX_Z;
        return checkX && checkY && checkZ;

    }

    // check for all spawns
    public static boolean isLocationASpawn(Location location) {
        return isLocation2024SpawnArea(location) || isLocation2025SpawnArea(location);
    }

    // Getter
    public static Location getSpawn2024Location() {
        return SPAWN_2024_LOCATION;
    }

    public static Location getLocation2025Location() {
        return SPAWN_2025_LOCATION;
    }

    public static Location getCurrentSpawn() {
        return getLocation2025Location();
    }

}
