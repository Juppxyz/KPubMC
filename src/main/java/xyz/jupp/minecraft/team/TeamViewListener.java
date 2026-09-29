package xyz.jupp.minecraft.team;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import xyz.jupp.minecraft.inventory.WarpView;

// hands the clicks of the team and warp menus to their views
public class TeamViewListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Object holder = event.getInventory().getHolder(false);
        if (holder instanceof TeamView view) {
            view.handleClick(event, player);
        } else if (holder instanceof TeamCreateView view) {
            view.handleClick(event, player);
        } else if (holder instanceof WarpView view) {
            view.handleClick(event, player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getInventory().getHolder(false);
        if (holder instanceof TeamView || holder instanceof TeamCreateView || holder instanceof WarpView) event.setCancelled(true);
    }

}
