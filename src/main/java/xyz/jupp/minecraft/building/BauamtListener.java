package xyz.jupp.minecraft.building;

import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import xyz.jupp.minecraft.utils.Tasks;

/**
 * Notices a finished copy of a template: every placed block or cushion that occurs in a template is a candidate for
 * the last piece of a copy. The check runs a tick later, when the neighbours (stairs shapes, fences) have settled, and
 * reads only the few cells around the piece, nothing more.
 * Breaking a piece of a paid build (state property) is charged to the player; for every other block of the world the
 * break check is a single map lookup.
 */
public class BauamtListener implements Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Material material = block.getType();
        if (!Bauamt.isTrigger(material)) return;
        Bauamt.queuePlaced(event.getPlayer(), block.getWorld(), block.getX(), block.getY(), block.getZ(), material, null);
    }

    // cushions are entities
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Cushion cushion)) return;
        Player player = event.getPlayer();
        if (player == null) return;
        Material material = Blueprint.cushionMaterial(cushion.getColor());
        if (!Bauamt.isTrigger(material)) return;
        Location at = cushion.getLocation();
        Bauamt.queuePlaced(player, at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), material, cushion);
    }

    // in case placing a cushion fires no entity place event: a right click with one in hand, the cushions around the
    // clicked block are checked a tick later (a second notice of the same copy is swallowed by the hint cooldown)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        ItemStack item = event.getItem();
        if (item == null || Blueprint.cushionColor(item.getType()) == null || !Bauamt.isTrigger(item.getType())) return;
        Block clicked = event.getClickedBlock();
        Player player = event.getPlayer();
        Material material = item.getType();
        Tasks.sync(() -> {
            if (!player.isOnline()) return;
            BoundingBox around = BoundingBox.of(clicked).expand(1.5);
            for (Entity entity : clicked.getWorld().getNearbyEntities(around, entity -> entity instanceof Cushion)) {
                if (Blueprint.cushionMaterial(((Cushion) entity).getColor()) != material) continue;
                Location at = entity.getLocation();
                Bauamt.queuePlaced(player, clicked.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), material, entity);
            }
        });
    }

    // state property: the block is still there during the event, so it can be compared with the template
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        Bauamt.blockBroken(event.getPlayer(), event.getBlock());
    }

    // TNT a player lit, or a fireball a player shot: the pieces it breaks are charged like broken by hand
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onExplode(EntityExplodeEvent event) {
        // only explosions that remove blocks (a wind charge lists blocks it merely triggers)
        ExplosionResult result = event.getExplosionResult();
        if (result != ExplosionResult.DESTROY && result != ExplosionResult.DESTROY_WITH_DECAY) return;
        Entity source = event.getEntity();
        Player player = source instanceof TNTPrimed tnt && tnt.getSource() instanceof Player igniter ? igniter
                : source instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (player != null) Bauamt.exploded(player, event.getEntity().getWorld(), event.blockList());
    }

    // pistons from outside would strip a paid build without a single broken block
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (Bauamt.pistonTouchesBuild(event.getBlock(), event.getBlocks(), event.getDirection(), true)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (Bauamt.pistonTouchesBuild(event.getBlock(), event.getBlocks(), event.getDirection(), false)) event.setCancelled(true);
    }

    // a cushion that vanishes after a player's hit
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCushionHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Cushion cushion)) return;
        Player player = event.getDamager() instanceof Player direct ? direct
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (player == null) return;
        Location at = cushion.getLocation();
        Tasks.sync(() -> {
            if (!cushion.isValid() && player.isOnline()) Bauamt.cushionBroken(player, at);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Tasks.syncLater(60L, () -> {
            if (player.isOnline()) Bauamt.remind(player);
        });
    }

}
