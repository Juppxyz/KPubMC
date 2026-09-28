package xyz.jupp.minecraft.listener;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.utils.ClaimedAreaHelper;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.EnumSet;
import java.util.Set;


public class MobLimiterListener implements Listener {

    private static final Set<EntityType> ALLOWED_ENTITIES = EnumSet.of(EntityType.VILLAGER, EntityType.CHICKEN, EntityType.IRON_GOLEM, EntityType.ARMOR_STAND, EntityType.WANDERING_TRADER, EntityType.VINDICATOR, EntityType.CAMEL, EntityType.HORSE);

    private static final int NEARBY_ENTITY_RADIUS_XZ = 8;
    private static final int NEARBY_ENTITY_RADIUS_Y = 2;
    private static final int NEARBY_ENTITY_LIMIT = 50;
    private static final long WITHER_SKELETON_LIFESPAN_TICKS = 600L;

    private static boolean isProtected(@Nullable TeamCacheObject claimingTeam) {
        return claimingTeam != null && claimingTeam.getLevel() >= 2 && !claimingTeam.isZoneOptionMobDamage();
    }

    // block coordinates only, no Location per block (explosions check every block)
    private static boolean isProtected(Block block) {
        return isProtected(ClaimedAreaHelper.getClaimingTeam(block));
    }

    private static boolean isProtected(Location location) {
        return isProtected(ClaimedAreaHelper.getClaimingTeam(location));
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

        if (entity instanceof WitherSkeleton witherSkeleton) {
            expandWitherSkeletonTime(witherSkeleton);
        }
    }


    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(block -> isProtected(block));
        if (isProtected(e.getLocation())) {
            e.setYield(0f);
        }
    }


    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(block -> isProtected(block));
        if (isProtected(e.getBlock())) {
            e.setYield(0f);
        }
    }


    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Entity ent = e.getEntity();
        boolean isMobOrDragon = (ent instanceof Mob) || (ent instanceof EnderDragon);
        if (isMobOrDragon && isProtected(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityBreakDoor(EntityBreakDoorEvent e) {
        if (isProtected(e.getBlock())) {
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
        // not saved: if the chunk unloads or the server stops before the task, it is gone instead of staying forever
        witherSkeleton.setPersistent(false);
        witherSkeleton.setTicksLived(1);

        Tasks.syncLater(WITHER_SKELETON_LIFESPAN_TICKS, () -> {
            if (witherSkeleton.isValid()) {
                witherSkeleton.remove();
            }
        });
    }

}
