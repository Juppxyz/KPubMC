package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.ChunkCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.utils.Locations;

import java.util.EnumSet;
import java.util.Set;


public class MobLimiterListener implements Listener {

    private static final Set<EntityType> ALLOWED_ENTITIES = EnumSet.of(EntityType.VILLAGER, EntityType.CHICKEN, EntityType.IRON_GOLEM, EntityType.ARMOR_STAND, EntityType.WANDERING_TRADER, EntityType.VINDICATOR, EntityType.CAMEL);

    private static final int NEARBY_ENTITY_RADIUS_XZ = 8;
    private static final int NEARBY_ENTITY_RADIUS_Y = 2;
    private static final int NEARBY_ENTITY_LIMIT = 50;
    private static final long WITHER_SKELETON_LIFESPAN_TICKS = 600L;

    private boolean isProtected(Location location) {
        ChunkCacheObject chunkCacheObject = ChunkCache.getInstance().getChunkObject(location.getWorld().getName(), location.getChunk().getX(), location.getChunk().getZ());
        if (chunkCacheObject == null) return false;

        TeamCacheObject teamCacheObject = CacheHandler.getInstance().getTeamCacheObject(chunkCacheObject.getTeamID());
        if (teamCacheObject == null) return false;
        boolean checkTeamLevel = teamCacheObject.getLevel() >= 2 ;
        return checkTeamLevel && !teamCacheObject.isZoneOptionMobDamage();
    }

    @EventHandler
    public void onSpawnCreature(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        EntityType entityType = event.getEntityType();
        Location location = entity.getLocation();

        if (Locations.isLocationASpawn(location) && !ALLOWED_ENTITIES.contains(entityType)) {
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


    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> isProtected(b.getLocation()));
        if (isProtected(e.getLocation())) {
            e.setYield(0f);
        }
    }


    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> isProtected(b.getLocation()));
        if (isProtected(e.getBlock().getLocation())) {
            e.setYield(0f);
        }
    }


    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Entity ent = e.getEntity();
        boolean isMobOrDragon = (ent instanceof Mob) || (ent instanceof EnderDragon);
        if (isMobOrDragon && isProtected(e.getBlock().getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityBreakDoor(EntityBreakDoorEvent e) {
        if (isProtected(e.getBlock().getLocation())) {
            e.setCancelled(true);
        }
    }


    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Entity remover = e.getRemover();
        if (!(remover instanceof Player) && isProtected(e.getEntity().getLocation())) {
            e.setCancelled(true);
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

}
