package xyz.jupp.minecraft.building;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Tasks;

/**
 * Notices a finished copy of a template: every placed block or cushion that occurs in a template is a candidate for
 * the last piece of a copy. The check runs a tick later, when the neighbours (stairs shapes, fences) have settled, and
 * reads only the few cells around the piece, nothing more.
 * Breaking a piece of a paid build (state property) is charged to the player, nothing else can destroy one; for every
 * other block of the world the check is a single map lookup.
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

    // state property does not burn, melt, wash away or blow up: a copy that could be destroyed without a breaker to
    // charge could be rebuilt from its own drops and paid again (only breaking by hand remains, and that is charged)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent event) {
        Bauamt.spareFromExplosion(event.getEntity().getWorld(), event.blockList());
    }

    // beds and respawn anchors in the wrong dimension
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        Bauamt.spareFromExplosion(event.getBlock().getWorld(), event.blockList());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBurn(BlockBurnEvent event) {
        if (Bauamt.isStatePiece(event.getBlock())) event.setCancelled(true);
    }

    // water and lava wash away rails, torches, levers ...; flowing into air breaks nothing (the usual case, no lookup)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (to.getType().isAir()) return;
        if (Bauamt.isStatePiece(to)) event.setCancelled(true);
    }

    // ice melting, coral dying, snow and farmland fading
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFade(BlockFadeEvent event) {
        if (Bauamt.isStatePiece(event.getBlock())) event.setCancelled(true);
    }

    // endermen, withers, ravagers, falling sand and gravel; players stripping, waxing, scraping, tilling or trampling a
    // piece (a changed piece would no longer count as one, then breaking it would be free); picking berries stays
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (event.getTo() == event.getBlock().getType() || PermissionsUtil.isPlayerAdmin(player)) return;
        }
        if (Bauamt.isStatePiece(event.getBlock())) event.setCancelled(true);
    }

    // a block placed over a replaceable piece (grass, snow, water ...)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlaceOverPiece(BlockPlaceEvent event) {
        if (PermissionsUtil.isPlayerAdmin(event.getPlayer())) return;
        if (Bauamt.replacesStatePiece(event.getBlock(), event.getBlockReplacedState().getBlockData())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (PermissionsUtil.isPlayerAdmin(event.getPlayer())) return;
        if (Bauamt.isStatePiece(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (PermissionsUtil.isPlayerAdmin(event.getPlayer())) return;
        if (Bauamt.isStatePiece(event.getBlock())) event.setCancelled(true);
    }

    // copper weathering, concrete hardening, ice and snow forming; grass, vines and sculk spreading; crops and saplings
    // growing: nothing changes a piece or fills a gap of a paid build by itself (one map lookup for any other block)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onForm(BlockFormEvent event) {
        if (Bauamt.inStateBox(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSpread(BlockSpreadEvent event) {
        if (Bauamt.inStateBox(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onGrow(BlockGrowEvent event) {
        if (Bauamt.inStateBox(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onStructureGrow(StructureGrowEvent event) {
        for (BlockState state : event.getBlocks()) {
            if (Bauamt.inStateBox(state.getBlock())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onCushionBlast(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Cushion cushion)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        if (Bauamt.isStateProp(cushion.getLocation())) event.setCancelled(true);
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
