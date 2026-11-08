package xyz.jupp.minecraft.jail;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.listener.TeamAreaListener;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Text;

/**
 * A prisoner a bounty hunter caught sits the sentence out in the cell: no interaction with the world (blocks, entities,
 * items, projectiles, pearls, food) and no damage, so no death either. Leaving the cell is handled by JailHandler.
 */
public class PrisonListener implements Listener {

    private static boolean locked(@Nullable Player player) {
        return player != null && player.isOnline() && JailHandler.isCaughtPrisoner(player);
    }

    private static void deny(Player player, Cancellable event) {
        event.setCancelled(true);
        player.sendActionBar(Text.of("§cDu sitzt deine Strafe ab."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (locked(event.getPlayer())) deny(event.getPlayer(), event);
    }

    // also the click on a precise spot of an entity (PlayerInteractAtEntityEvent shares this event's handlers)
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (locked(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (locked(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (locked(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (locked(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (locked(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (locked(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer())) deny(event.getPlayer(), event);
    }

    // nothing thrown in through the bars reaches them
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && locked(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (locked(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getShooter() instanceof Player player && locked(player)) deny(player, event);
    }

    // a pearl or chorus fruit that got through anyway; the teleport back into the cell (PLUGIN) stays possible
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if ((cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT)
                && locked(event.getPlayer())) {
            deny(event.getPlayer(), event);
        }
    }

    // no attacks from inside the cell: also not by a projectile shot before the arrest, a pet still fighting, TNT or a cloud
    // (each would also start a fight, and a combat logout kills)
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = TeamAreaListener.responsiblePlayer(event);
        if (locked(attacker)) deny(attacker, event);
    }

    // boats, minecarts, paintings and item frames within reach
    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (event.getAttacker() instanceof Player player && locked(player)) deny(player, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getAttacker() instanceof Player player && locked(player)) deny(player, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player && locked(player)) deny(player, event);
    }

    // any menu or container that opens anyway (the NPC menus react to cancelled clicks, see ShopListener)
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && locked(player)) deny(player, event);
    }

    // no damage of any kind: players, mobs, fire, suffocation, starvation; they cannot die in the cell
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && locked(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && locked(player) && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

}
