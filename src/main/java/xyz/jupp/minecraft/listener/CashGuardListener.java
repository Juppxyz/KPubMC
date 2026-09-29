package xyz.jupp.minecraft.listener;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import org.bukkit.block.BlockState;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.Cash;

/**
 * Cash (the notes and the old emerald cash) is money, not material: no crafting with it, no anvil, cartography table or
 * other station, and villagers do not take it (they would count an old cash emerald as a real one).
 */
public class CashGuardListener implements Listener {

    private static boolean containsCash(ItemStack[] items) {
        for (ItemStack item : items) {
            if (Cash.isAnyCash(item)) return true;
        }
        return false;
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        if (containsCash(event.getInventory().getMatrix())) event.getInventory().setResult(null);
    }

    // anvil, smithing table, grindstone, cartography table, loom and stonecutter
    @EventHandler
    public void onStation(PrepareResultEvent event) {
        if (containsCash(event.getInventory().getContents())) event.setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        BlockState state = event.getBlock().getState(false);
        if (state instanceof Crafter crafter && containsCash(crafter.getInventory().getContents())) event.setCancelled(true);
    }

    // Villagers: cash never goes into the payment slots, and with cash in them the result cannot be taken.
    // Blocked on the click, before the server moves anything: cancelling the purchase event instead would leave the
    // result on a matching cursor stack for free.
    @EventHandler(ignoreCancelled = true)
    public void onMerchantClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory() instanceof MerchantInventory merchant)) return;
        int slot = event.getRawSlot();
        boolean intoPayment = slot == 0 || slot == 1;
        // any way of taking the result (click, shift, number key, swap) while cash pays for it
        boolean takesResult = slot == 2 && paysWithCash(merchant);
        boolean movesCashIn = switch (event.getClick()) {
            case SHIFT_LEFT, SHIFT_RIGHT -> slot >= merchant.getSize() && Cash.isAnyCash(event.getCurrentItem());
            case NUMBER_KEY -> intoPayment && Cash.isAnyCash(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()));
            case SWAP_OFFHAND -> intoPayment && Cash.isAnyCash(event.getWhoClicked().getInventory().getItemInOffHand());
            default -> intoPayment && Cash.isAnyCash(event.getCursor());
        };
        if (takesResult || movesCashIn) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(Main.getChatPrefix() + "§cBargeld ist Geld, kein Handelsgut. §7Zahl es bei Basil ein.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory() instanceof MerchantInventory)) return;
        if (Cash.isAnyCash(event.getOldCursor()) && (event.getRawSlots().contains(0) || event.getRawSlots().contains(1))) event.setCancelled(true);
    }

    // the trade selection can fill the payment slots by itself, so the result is checked as well
    private static boolean paysWithCash(MerchantInventory merchant) {
        return Cash.isAnyCash(merchant.getItem(0)) || Cash.isAnyCash(merchant.getItem(1));
    }

}
