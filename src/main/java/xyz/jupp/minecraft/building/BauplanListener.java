package xyz.jupp.minecraft.building;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import xyz.jupp.minecraft.Main;

/**
 * The building plan in the main hand: a right click turns the page (sneaking: back), a left click on a block checks the
 * copy there. In the off hand it only shows its layer while the player builds.
 */
public class BauplanListener implements Listener {

    // not ignoreCancelled: a click into the air always arrives cancelled
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (!Bauplan.isPlan(item)) return;
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                // a hit with the plan never breaks anything (a torch or a rail would go at once)
                event.setCancelled(true);
                if (clicked == null) return;
                Blueprint template = Bauamt.template(Bauplan.templateId(item));
                if (template == null) {
                    player.sendMessage(Main.getChatPrefix() + "§fDiese Vorlage gibt es beim Bauamt nicht mehr.");
                    return;
                }
                PlanCheck.check(player, clicked, template);
            }
            case RIGHT_CLICK_BLOCK -> {
                // doors, chests and levers still work with the plan in hand
                if (!player.isSneaking() && clicked != null && clicked.getType().isInteractable()) return;
                event.setCancelled(true);
                Bauplan.flip(player, player.isSneaking());
            }
            case RIGHT_CLICK_AIR -> {
                event.setCancelled(true);
                Bauplan.flip(player, player.isSneaking());
            }
            default -> {}
        }
    }

    // a fixed cell loses its mark
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        PlanCheck.changed(event.getPlayer(), event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        PlanCheck.changed(event.getPlayer(), event.getBlock());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PlanCheck.forget(event.getPlayer().getUniqueId());
        Bauplan.forget(event.getPlayer().getUniqueId());
    }

}
