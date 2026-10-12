package xyz.jupp.minecraft.building;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
        Player player = event.getPlayer();
        Tasks.sync(() -> {
            if (player.isOnline() && block.getType() == material) {
                Bauamt.placed(player, block.getWorld(), block.getX(), block.getY(), block.getZ(), material);
            }
        });
    }

    // cushions are entities
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Cushion cushion)) return;
        Player player = event.getPlayer();
        if (player == null) return;
        Material material = Blueprint.cushionMaterial(cushion.getColor());
        if (!Bauamt.isTrigger(material)) return;
        Tasks.sync(() -> {
            if (!player.isOnline() || !cushion.isValid()) return;
            Location at = cushion.getLocation();
            Bauamt.placed(player, at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), material);
        });
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
                Bauamt.placed(player, clicked.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), material);
            }
        });
    }

    // state property: the block is still there during the event, so it can be compared with the template
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        Bauamt.blockBroken(event.getPlayer(), event.getBlock());
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
