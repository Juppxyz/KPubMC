package xyz.jupp.minecraft.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.PermissionsUtil;

/**
 * The spawn areas belong to the server: only admins change them. A block counts by where it is, not by where the
 * player stands, so nobody builds or digs into the spawn from just outside; explosions, liquids from outside, frames
 * and armor stands are covered too.
 */
public class SpawnListener implements Listener {

    @EventHandler
    public void blockLightnings(@NotNull LightningStrikeEvent event) {
        if (Locations.isLocationASpawn(event.getLightning().getLocation())) {
            event.getLightning().remove();
        }
    }


    @EventHandler
    public void onInteractAtSpawn(@NotNull PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null || block.getType() == Material.AIR) return;
        // most interactions happen outside the spawn, so the area checks come first
        boolean playerInSpawn = Locations.isLocationASpawn(player.getLocation());
        if (!playerInSpawn && !Locations.isSpawnBlock(block)) return;
        if (PermissionsUtil.isPlayerAdmin(player)) return;

        if (event.getAction().equals(Action.PHYSICAL)) {
            if (block.getType() == Material.STONE_PRESSURE_PLATE) return;
        }

        if (event.getAction().isRightClick()) {
            if (block.getType() == Material.BIRCH_WALL_SIGN) return;
            if (block.getType() == Material.STONE_BUTTON) return;
            if (block.getType() == Material.ENDER_CHEST) return;
            if (block.getType().name().contains("DOOR") && !block.getType().name().contains("TRAPDOOR")) return;
        }

        // standing outside and only aiming at a spawn block: the block is off limits (no breaking, no using it),
        // the item in hand still works (eating, a shield, a bow)
        if (!playerInSpawn) {
            event.setUseInteractedBlock(Event.Result.DENY);
            return;
        }
        event.setCancelled(true);
    }

    // placed against a block outside the spawn, the new block may still land inside
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        if (Locations.isSpawnBlock(event.getBlockPlaced()) && !PermissionsUtil.isPlayerAdmin(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (Locations.isSpawnBlock(event.getBlock()) && !PermissionsUtil.isPlayerAdmin(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (Locations.isSpawnBlock(event.getBlock()) && !PermissionsUtil.isPlayerAdmin(event.getPlayer())) event.setCancelled(true);
    }

    // lava or water poured just outside does not flow in
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (Locations.isSpawnBlock(to) && !Locations.isSpawnBlock(event.getBlock())) event.setCancelled(true);
    }

    // fire from fireballs, lava or a lighter (only admins light anything here)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onIgnite(BlockIgniteEvent event) {
        if (!Locations.isSpawnBlock(event.getBlock())) return;
        Player player = event.getPlayer();
        if (player == null || !PermissionsUtil.isPlayerAdmin(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(Locations::isSpawnBlock);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(Locations::isSpawnBlock);
    }


    /* frames, paintings and armor stands of the spawn */

    private static boolean decoration(Entity entity) {
        return entity instanceof Hanging || entity instanceof ArmorStand;
    }

    private static boolean deny(@Nullable Player player, @NotNull Entity entity) {
        return player != null && decoration(entity) && Locations.isLocationASpawn(entity.getLocation()) && !PermissionsUtil.isPlayerAdmin(player);
    }

    private static @Nullable Player playerBehind(@Nullable Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamageDecoration(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (decoration(victim) && deny(TeamAreaListener.responsiblePlayer(event), victim)) event.setCancelled(true);
    }

    // frames, paintings and armor stands placed into the spawn from outside
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (deny(event.getPlayer(), event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (deny(event.getPlayer(), event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        // explosions and mobs: only the decoration of the spawn
        if (remover == null || playerBehind(remover) == null) {
            if (Locations.isLocationASpawn(event.getEntity().getLocation())) event.setCancelled(true);
            return;
        }
        if (deny(playerBehind(remover), event.getEntity())) event.setCancelled(true);
    }

}
