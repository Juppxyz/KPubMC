package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public class Locations {

    private static final String MAIN_WORLD_NAME = "world_MCWinter";
    // looked up on first use, the world may not be loaded yet when this class is initialised
    private static volatile World mainWorld;

    private static final double SPAWN_2024_MIN_X = 92508.0D;
    private static final double SPAWN_2024_MAX_X = 92810.0D;
    private static final double SPAWN_2024_MIN_Z = 114375.0D;
    private static final double SPAWN_2024_MAX_Z = 114626.0D;
    private static final double SPAWN_2024_Y = -64.0D;

    private static final double SPAWN_2025_MAX_X = 150200.0D;
    private static final double SPAWN_2025_MAX_Z = 150348.0D;
    private static final double SPAWN_2025_MIN_X = 149966.0D;
    private static final double SPAWN_2025_MIN_Z = 150200.0D;
    private static final double SPAWN_2025_Y = -64.0D;


    private static World mainWorld() {
        World world = mainWorld;
        if (world == null) {
            world = Bukkit.getWorld(MAIN_WORLD_NAME);
            mainWorld = world;
        }
        return world;
    }

    private static boolean isInMainWorld(Location location) {
        World world = mainWorld();
        return world != null && location.getWorld() != null && location.getWorld().getName().equals(world.getName());
    }

    // Spawn - 2024
    private static boolean isIn2024SpawnArea(double x, double y, double z) {
        boolean checkX = x >= SPAWN_2024_MIN_X && x <= SPAWN_2024_MAX_X;
        boolean checkY = y >= SPAWN_2024_Y;
        boolean checkZ = z >= SPAWN_2024_MIN_Z && z <= SPAWN_2024_MAX_Z;
        return checkX && checkY && checkZ;
    }

    // Spawn - 2025
    private static boolean isIn2025SpawnArea(double x, double y, double z) {
        boolean checkX = x >= SPAWN_2025_MIN_X && x <= SPAWN_2025_MAX_X;
        boolean checkY = y >= SPAWN_2025_Y;
        boolean checkZ = z >= SPAWN_2025_MIN_Z && z <= SPAWN_2025_MAX_Z;
        return checkX && checkY && checkZ;
    }

    // check for all spawns
    public static boolean isLocationASpawn(Location location) {
        if (!isInMainWorld(location)) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return isIn2024SpawnArea(x, y, z) || isIn2025SpawnArea(x, y, z);
    }

    // Getter (new instances, so callers cannot move the shared positions)
    public static Location getCurrentSpawn() {
        return new Location(mainWorld(), 150069.500, 240, 150293.500, 180, 0);
    }

    public static Location getJailCorner1() {
        return new Location(mainWorld(), 150038, 235, 150225);
    }
    public static Location getJailCorner2() {
        return new Location(mainWorld(), 150045, 248, 150233);
    }
    public static Location getJailSpawn() {
        return new Location(mainWorld(), 150041, 239.500, 150228);
    }
}
