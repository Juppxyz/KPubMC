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
        if (event.getInventory().getHolder(false) instanceof BlackMarketView view) view.handleClick(event, player);
        if (event.getInventory().getHolder(false) instanceof HondoView view) view.handleClick(event, player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getInventory().getHolder(false);
        if (holder instanceof ShopView || holder instanceof NomadView || holder instanceof BlackMarketView || holder instanceof HondoView) {
            event.setCancelled(true);
        }
    }

}
