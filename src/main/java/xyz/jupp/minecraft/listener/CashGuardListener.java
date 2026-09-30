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
import org.bukkit.entity.Piglin;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import xyz.jupp.minecraft.Main;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.economy.Cash;
import xyz.jupp.minecraft.economy.Goods;

/**
 * Cash (the notes and the old emerald cash) is money, not material: no crafting with it, no anvil, cartography table or
 * other station, and villagers do not take it (they would count an old cash emerald as a real one).
 * Bought goods (Goods) are no payment for villagers or piglins either: their trades would turn shop goods into plain
 * goods Nomad takes.
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
        ItemStack refused = slot == 2 ? payment(merchant) : null;
        if (refused == null) {
            ItemStack moving = switch (event.getClick()) {
                case SHIFT_LEFT, SHIFT_RIGHT -> slot >= merchant.getSize() ? event.getCurrentItem() : null;
                case NUMBER_KEY -> intoPayment ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton()) : null;
                case SWAP_OFFHAND -> intoPayment ? event.getWhoClicked().getInventory().getItemInOffHand() : null;
                default -> intoPayment ? event.getCursor() : null;
            };
            if (isRefused(moving)) refused = moving;
        }
        if (refused != null) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(Main.getChatPrefix() + refusal(refused));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory() instanceof MerchantInventory)) return;
        if (isRefused(event.getOldCursor()) && (event.getRawSlots().contains(0) || event.getRawSlots().contains(1))) event.setCancelled(true);
    }

    // the trade selection can fill the payment slots by itself, so the result is checked as well
    private static @Nullable ItemStack payment(MerchantInventory merchant) {
        if (isRefused(merchant.getItem(0))) return merchant.getItem(0);
        if (isRefused(merchant.getItem(1))) return merchant.getItem(1);
        return null;
    }

    private static boolean isRefused(@Nullable ItemStack item) {
        return Cash.isAnyCash(item) || Goods.isBought(item);
    }

    private static String refusal(ItemStack item) {
        return Cash.isAnyCash(item) ? "§cBargeld ist Geld, kein Handelsgut. §7Zahl es bei Basil ein."
                : "§cGekaufte Ware nehmen Händler nicht als Bezahlung. §7Der Laden nimmt sie zurück.";
    }

    // piglins barter with gold: not with bought gold, neither handed over nor thrown
    @EventHandler(ignoreCancelled = true)
    public void onPiglinHand(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Piglin)) return;
        ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (Goods.isBought(hand)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Main.getChatPrefix() + "§cGekauftes Gold nehmen Piglins nicht.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiglinPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Piglin && Goods.isBought(event.getItem().getItemStack())) event.setCancelled(true);
    }

}
