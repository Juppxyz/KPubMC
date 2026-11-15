package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin tool: right click fences with a stick to tie leads between them (decoration).
 * A lead needs a mob on one end, so the second fence gets an invisible, tiny, frozen rabbit (the anchor)
 * leashed to the knot of the first fence. The knot on the second fence is only for the look.
 * Vanilla never saves knots: it rebuilds a holder's knot from the anchor's lead when the chunk loads, the knot on
 * the anchor's own fence is put back here. So a knot is part of a tie if an anchor hangs on it or sits on its fence.
 */
public class FenceLeashListener implements Listener {

    // marks the anchors
    private static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "fence_leash");
    // leads snap at 12 blocks (Leashable.LEASH_TOO_FAR_DIST)
    private static final double MAX_DISTANCE = 10;
    // LeashFenceKnotEntity: the knot sits at block y + 0.375 and holds the rope 0.2 above that
    private static final double ROPE_Y = 0.575;
    private static final double SEARCH_RADIUS = 13;

    // admin -> fence the next click ties to (main thread)
    private final Map<UUID, Block> selected = new HashMap<>();

    public static boolean isAnchor(Entity entity) {
        return entity instanceof Rabbit && entity.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    private static boolean isTieKnot(LeashHitch knot) {
        return !anchorsAt(knot.getLocation().getBlock()).isEmpty() || !anchorsTiedTo(knot).isEmpty();
    }

    private static boolean isAdminWithStick(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.STICK && PermissionsUtil.isPlayerAdmin(player);
    }

    private static Location center(Block block) {
        return block.getLocation().add(0.5, 0.5, 0.5);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFenceClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || !Tag.FENCES.isTagged(block.getType()) || !isAdminWithStick(event.getPlayer())) return;
        event.setCancelled(true);
        clickFence(event.getPlayer(), block);
    }

    // a knot (or an anchor) covers the middle of its fence, so clicking there hits the entity instead of the block
    @EventHandler(priority = EventPriority.LOW)
    public void onKnotClick(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!isAnchor(clicked) && !(clicked instanceof LeashHitch)) return;
        Player player = event.getPlayer();
        if (isAdminWithStick(player)) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND) clickFence(player, clicked.getLocation().getBlock());
            return;
        }
        // vanilla would take the knot off and the leads would drop
        if (isAnchor(clicked) || isTieKnot((LeashHitch) clicked)) event.setCancelled(true);
    }

    private void clickFence(Player player, Block fence) {
        UUID id = player.getUniqueId();
        if (player.isSneaking()) {
            selected.remove(id);
            int removed = untie(fence);
            player.sendActionBar(Text.of(removed == 0 ? "§7An diesem Zaun hängt keine Leine."
                    : "§7" + removed + (removed == 1 ? " Leine" : " Leinen") + " entfernt."));
            return;
        }
        Block from = selected.get(id);
        if (from == null || !from.getWorld().equals(fence.getWorld()) || !Tag.FENCES.isTagged(from.getType())) {
            selected.put(id, fence);
            player.sendActionBar(Text.of("§7Zaun gewählt, jetzt den nächsten Zaun anklicken. §fSchleichen §7+ Klick löst Leinen."));
            return;
        }
        if (from.equals(fence)) {
            selected.remove(id);
            player.sendActionBar(Text.of("§7Auswahl beendet."));
            return;
        }
        if (center(from).distance(center(fence)) > MAX_DISTANCE) {
            player.sendActionBar(Text.of("§cZu weit weg, höchstens " + (int) MAX_DISTANCE + " Blöcke."));
            return;
        }
        // the clicked fence is the start of the next lead, so a row of fences is just one click each
        selected.put(id, fence);
        if (tiedTo(from, fence) || tiedTo(fence, from)) {
            player.sendActionBar(Text.of("§7Die beiden Zäune sind schon verbunden."));
            return;
        }
        tie(from, fence);
        player.sendActionBar(Text.of("§aVerbunden. §7Nächster Zaun verbindet weiter, derselbe Zaun beendet."));
    }

    private static void tie(Block holderFence, Block anchorFence) {
        LeashHitch holder = knot(holderFence, true);
        knot(anchorFence, true);
        anchorFence.getWorld().playSound(center(anchorFence), Sound.ITEM_LEAD_TIED, 1f, 1f);
        Location at = anchorFence.getLocation().add(0.5, ROPE_Y, 0.5);
        Rabbit anchor = anchorFence.getWorld().spawn(at, Rabbit.class, rabbit -> {
            rabbit.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            rabbit.setInvisible(true);
            rabbit.setSilent(true);
            rabbit.setAI(false);
            rabbit.setGravity(false);
            rabbit.setInvulnerable(true);
            rabbit.setCollidable(false);
            rabbit.setPersistent(true);
            rabbit.setRemoveWhenFarAway(false);
            // smallest scale: the rope starts at its eyes, which are then where the knot holds the rope
            AttributeInstance scale = rabbit.getAttribute(Attribute.SCALE);
            if (scale != null) scale.setBaseValue(0.0625);
        });
        anchor.setLeashHolder(holder);
    }

    private static @Nullable LeashHitch knot(Block fence, boolean create) {
        for (LeashHitch hitch : fence.getWorld().getNearbyEntitiesByType(LeashHitch.class, center(fence), 1)) {
            if (hitch.getLocation().getBlock().equals(fence)) return hitch;
        }
        return create ? fence.getWorld().spawn(fence.getLocation(), LeashHitch.class) : null;
    }

    private static List<Rabbit> anchorsAt(Block fence) {
        return List.copyOf(fence.getWorld().getNearbyEntitiesByType(Rabbit.class, center(fence), 1,
                rabbit -> isAnchor(rabbit) && rabbit.getLocation().getBlock().equals(fence)));
    }

    private static List<Rabbit> anchorsTiedTo(LeashHitch knot) {
        return List.copyOf(knot.getWorld().getNearbyEntitiesByType(Rabbit.class, knot.getLocation(), SEARCH_RADIUS,
                rabbit -> isAnchor(rabbit) && knot.equals(holderOf(rabbit))));
    }

    private static @Nullable LeashHitch holderOf(LivingEntity entity) {
        return entity.isLeashed() && entity.getLeashHolder() instanceof LeashHitch hitch ? hitch : null;
    }

    private static boolean tiedTo(Block holderFence, Block anchorFence) {
        for (Rabbit anchor : anchorsAt(anchorFence)) {
            LeashHitch holder = holderOf(anchor);
            if (holder != null && holder.getLocation().getBlock().equals(holderFence)) return true;
        }
        return false;
    }

    // every lead at this fence, both ends
    private static int untie(Block fence) {
        Set<LeashHitch> knots = new HashSet<>();
        int removed = 0;
        for (Rabbit anchor : anchorsAt(fence)) {
            LeashHitch holder = holderOf(anchor);
            if (holder != null) knots.add(holder);
            anchor.remove();
            removed++;
        }
        LeashHitch knot = knot(fence, false);
        if (knot != null) {
            knots.add(knot);
            for (Rabbit anchor : anchorsTiedTo(knot)) {
                LeashHitch far = knot(anchor.getLocation().getBlock(), false);
                if (far != null) knots.add(far);
                anchor.remove();
                removed++;
            }
        }
        knots.forEach(FenceLeashListener::removeIfUnused);
        return removed;
    }

    // a knot of a tie with nothing tied to it (players' animals included) and no anchor at its fence is left over
    private static void removeIfUnused(LeashHitch knot) {
        if (!knot.isValid() || !anchorsAt(knot.getLocation().getBlock()).isEmpty()) return;
        boolean holds = !knot.getWorld().getNearbyEntitiesByType(LivingEntity.class, knot.getLocation(), SEARCH_RADIUS,
                entity -> entity.isValid() && knot.equals(holderOf(entity))).isEmpty();
        if (!holds) knot.remove();
    }

    // an anchor without its lead is pointless (knot broken, fence gone): it goes, and no lead drops
    @EventHandler
    public void onAnchorUnleash(EntityUnleashEvent event) {
        if (!isAnchor(event.getEntity())) return;
        event.setDropLeash(false);
        Rabbit anchor = (Rabbit) event.getEntity();
        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
            if (!anchor.isValid() || anchor.isLeashed()) return;
            LeashHitch knot = knot(anchor.getLocation().getBlock(), false);
            anchor.remove();
            if (knot != null) removeIfUnused(knot);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnchorUnleashByPlayer(PlayerUnleashEntityEvent event) {
        if (isAnchor(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onKnotHit(HangingBreakByEntityEvent event) {
        if (!(event.getEntity() instanceof LeashHitch knot) || !isTieKnot(knot)) return;
        if (!(event.getRemover() instanceof Player player) || !PermissionsUtil.isPlayerAdmin(player)) event.setCancelled(true);
    }

    // the knot is gone (an admin hit it, the fence broke): the anchors on its fence go too
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onKnotBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof LeashHitch)) return;
        Block fence = event.getEntity().getLocation().getBlock();
        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
            if (event.getEntity().isValid()) return;
            for (Rabbit anchor : anchorsAt(fence)) {
                LeashHitch holder = holderOf(anchor);
                anchor.remove();
                if (holder != null) removeIfUnused(holder);
            }
        });
    }

    // puts the knot on the anchor's fence back (vanilla does not save knots); a fence gone meanwhile takes the anchor
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!isAnchor(entity)) continue;
            Rabbit anchor = (Rabbit) entity;
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                if (!anchor.isValid()) return;
                Block fence = anchor.getLocation().getBlock();
                if (Tag.FENCES.isTagged(fence.getType())) {
                    knot(fence, true);
                    return;
                }
                LeashHitch holder = holderOf(anchor);
                anchor.remove();
                if (holder != null) removeIfUnused(holder);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selected.remove(event.getPlayer().getUniqueId());
    }

}
