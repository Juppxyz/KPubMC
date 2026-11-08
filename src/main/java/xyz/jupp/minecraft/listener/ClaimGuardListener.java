package xyz.jupp.minecraft.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.team.Relations;
import xyz.jupp.minecraft.team.Teams;
import xyz.jupp.minecraft.utils.ClaimedAreaHelper;
import xyz.jupp.minecraft.utils.Text;

import java.util.List;

/**
 * The lock of a team area ("Fremde Interaktionen" off, from INTERACTION_LEVEL) beyond clicks on blocks
 * (TeamAreaListener): strangers can neither use nor break things that are entities (chest minecarts and boats,
 * armor stands, item frames, paintings, vehicles), nor place blocks, liquids or frames into the area from outside.
 * From ANIMAL_LEVEL the area's animals and villagers are safe from them too; monsters stay fair game.
 * Liquids, fire, pistons and dispensers outside a locked area do not reach into it.
 * Every check is a chunk-map lookup; the physics events compare chunk coordinates first.
 */
public class ClaimGuardListener implements Listener {

    /** The locked area at this block, null in the wilderness or an open area. */
    static @Nullable TeamCacheObject locked(@NotNull World world, int x, int z) {
        TeamCacheObject team = ClaimedAreaHelper.getClaimingTeam(world, x, z);
        return team != null && ClaimedAreaHelper.isLocked(team) ? team : null;
    }

    // the locked area at this block for this player: null if none, or the player's own or a partner's
    private static @Nullable TeamCacheObject lockedFor(@NotNull World world, int x, int z, @NotNull Player player) {
        TeamCacheObject team = locked(world, x, z);
        if (team == null) return null;
        // a shooter who left meanwhile is not in the cache (it would load on the main thread): a stranger then
        String own = player.isOnline() ? CacheHandler.getInstance().getPlayerInCache(player).getTeamID() : null;
        if (team.getTeamID().equals(own) || Relations.partners(team.getTeamID(), own)) return null;
        return team;
    }

    private static @Nullable TeamCacheObject lockedFor(@NotNull Block block, @NotNull Player player) {
        return lockedFor(block.getWorld(), block.getX(), block.getZ(), player);
    }

    // things are protected by the lock itself, living ones (not monsters) from ANIMAL_LEVEL
    private static boolean protects(@NotNull TeamCacheObject team, @NotNull Entity entity) {
        if (entity instanceof Player) return false;
        if (entity instanceof ArmorStand || !(entity instanceof LivingEntity)) return true;
        if (entity instanceof Enemy) return false;
        return team.getLevel() >= Teams.ANIMAL_LEVEL;
    }

    private static boolean deny(@NotNull Player player, @NotNull Entity entity) {
        if (entity instanceof Player) return false;
        // the player's own horse, wolf or cat stays theirs, wherever it stands
        if (entity instanceof Tameable tameable && player.getUniqueId().equals(tameable.getOwnerUniqueId())) return false;
        Location at = entity.getLocation();
        TeamCacheObject team = lockedFor(at.getWorld(), at.getBlockX(), at.getBlockZ(), player);
        return team != null && protects(team, entity);
    }

    private static void refuse(@NotNull Cancellable event, @NotNull Player player) {
        event.setCancelled(true);
        player.sendActionBar(Text.of("§cDieses Gebiet ist für Fremde gesperrt."));
    }

    private static @Nullable Player playerBehind(@Nullable Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    // true if `to` lies in a locked area that `from` does not belong to (neither the same team nor a partner's)
    private static boolean crossesInto(@NotNull Block from, @NotNull Block to) {
        if ((from.getX() >> 4) == (to.getX() >> 4) && (from.getZ() >> 4) == (to.getZ() >> 4)) return false;
        TeamCacheObject target = locked(to.getWorld(), to.getX(), to.getZ());
        if (target == null) return false;
        TeamCacheObject source = ClaimedAreaHelper.getClaimingTeam(from);
        if (source == null) return true;
        String a = source.getTeamID(), b = target.getTeamID();
        return !a.equals(b) && !Relations.partners(a, b);
    }


    /* entities: using and breaking them */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked())) refuse(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked())) refuse(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked())) refuse(event, event.getPlayer());
    }

    // armor stands, frames (their item), crystals, and from ANIMAL_LEVEL animals and villagers; also arrows, TNT, tamed wolves
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (victim instanceof Player) return;
        Player attacker = TeamAreaListener.responsiblePlayer(event);
        if (attacker == null) return;
        // self-defence: a mob that is after the player (a golem, a sent wolf, bees) may be hit back
        if (victim instanceof Mob mob && mob.getTarget() == attacker) return;
        if (deny(attacker, victim)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player player = playerBehind(event.getRemover());
        if (player != null && deny(player, event.getEntity())) refuse(event, player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && deny(player, event.getEntity())) refuse(event, player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player player = playerBehind(event.getAttacker());
        if (player != null && deny(player, event.getVehicle())) refuse(event, player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Player player = playerBehind(event.getAttacker());
        if (player != null && deny(player, event.getVehicle())) refuse(event, player);
    }


    /* blocks and liquids placed into the area from outside (a click on a block outside passes the click lock) */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        if (lockedFor(event.getBlockPlaced(), event.getPlayer()) != null) refuse(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (lockedFor(event.getBlock(), event.getPlayer()) != null) refuse(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent event) {
        // milking is a use of the animal (protected from ANIMAL_LEVEL, by the entity rule), not of a block
        if (event.getItemStack() != null && event.getItemStack().getType() == Material.MILK_BUCKET) return;
        if (lockedFor(event.getBlock(), event.getPlayer()) != null) refuse(event, event.getPlayer());
    }

    // a fishing rod would drag protected entities (chest boats, carts, animals) out of the area
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFish(PlayerFishEvent event) {
        Entity caught = event.getCaught();
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && caught != null && deny(event.getPlayer(), caught)) {
            refuse(event, event.getPlayer());
        }
    }

    // armor stands, boats, carts and crystals placed into the area from outside
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && deny(player, event.getEntity())) refuse(event, player);
    }


    /* physics across the border */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFlow(BlockFromToEvent event) {
        if (crossesInto(event.getBlock(), event.getToBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onIgnite(BlockIgniteEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (player != null) {
            if (lockedFor(block, player) != null) refuse(event, player);
            return;
        }
        Block source = event.getIgnitingBlock();
        if (source != null && crossesInto(source, block)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBurn(BlockBurnEvent event) {
        Block source = event.getIgnitingBlock();
        if (source != null && crossesInto(source, event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonCrosses(event.getBlock(), event.getBlocks(), event.getDirection(), true)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonCrosses(event.getBlock(), event.getBlocks(), event.getDirection(), false)) event.setCancelled(true);
    }

    // direction: where the blocks move
    private static boolean pistonCrosses(Block piston, List<Block> moved, BlockFace direction, boolean extending) {
        if (extending && crossesInto(piston, piston.getRelative(direction))) return true;
        for (Block block : moved) {
            if (crossesInto(piston, block) || crossesInto(piston, block.getRelative(direction))) return true;
        }
        return false;
    }

    // lava, water, fire charges, TNT from a dispenser outside
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        if (block.getBlockData() instanceof Directional directional && crossesInto(block, block.getRelative(directional.getFacing()))) {
            event.setCancelled(true);
        }
    }

}
