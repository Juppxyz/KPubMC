package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import xyz.jupp.minecraft.Main;

import java.util.EnumSet;
import java.util.Set;

public class MobLimiterListener implements Listener {

    private static final Set<EntityType> ALLOWED_ENTITIES = EnumSet.of(EntityType.VILLAGER, EntityType.CHICKEN, EntityType.IRON_GOLEM);
    private static final double SPAWN_MIN_X = 92508.0D;
    private static final double SPAWN_MAX_X = 92810.0D;
    private static final double SPAWN_MIN_Z = 114375.0D;
    private static final double SPAWN_MAX_Z = 114626.0D;
    private static final double SPAWN_Y = 200.0D;

    private static final int NEARBY_ENTITY_RADIUS_XZ = 8;
    private static final int NEARBY_ENTITY_RADIUS_Y = 2;
    private static final int NEARBY_ENTITY_LIMIT = 50;
    private static final long WITHER_SKELETON_LIFESPAN_TICKS = 600L;

    @EventHandler
    public void onSpawnCreature(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        EntityType entityType = event.getEntityType();
        Location location = entity.getLocation();

        if (isLocationSpawnArea(location) && !ALLOWED_ENTITIES.contains(entityType)) {
            entity.remove();
            return;
        }

        int nearbyEntities = entity.getNearbyEntities(NEARBY_ENTITY_RADIUS_XZ, NEARBY_ENTITY_RADIUS_Y, NEARBY_ENTITY_RADIUS_XZ).size();
        if (nearbyEntities > NEARBY_ENTITY_LIMIT) {
            entity.remove();
            return;
        }

        if (entityType == EntityType.WITHER_SKELETON) {
            expandWitherSkeletonTime((WitherSkeleton) entity);
        }
    }

    private void expandWitherSkeletonTime(WitherSkeleton witherSkeleton) {
        witherSkeleton.setRemoveWhenFarAway(false);
        witherSkeleton.setTicksLived(1);

        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            if (witherSkeleton.isValid()) {
                witherSkeleton.remove();
            }
        }, WITHER_SKELETON_LIFESPAN_TICKS);
    }

    private static boolean isLocationSpawnArea(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals("world_MCWinter")) {
            return false;
        }

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        boolean checkX = x >= SPAWN_MIN_X && x <= SPAWN_MAX_X;
        boolean checkY = y >= SPAWN_Y;
        boolean checkZ = z >= SPAWN_MIN_Z && z <= SPAWN_MAX_Z;

        return checkX && checkY && checkZ;
    }
}
