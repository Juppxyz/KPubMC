package xyz.jupp.minecraft.economy;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class ShopViewListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory().getHolder(false) instanceof ShopView view) view.handleClick(event, player);
        if (event.getInventory().getHolder(false) instanceof NomadView view) view.handleClick(event, player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof ShopView || event.getInventory().getHolder(false) instanceof NomadView) {
            event.setCancelled(true);
        }
    }

}
