package xyz.jupp.minecraft.inventory;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

public class JewelerInventory {

    // NOT IN USE

    public enum JewelerInventoryType {MAIN}

    public static void openInventory(@NotNull Player player, @NotNull JewelerInventoryType jewelerInventoryType) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                player.closeInventory();
                if (jewelerInventoryType.equals(JewelerInventoryType.MAIN)) {
                    player.openInventory(createNewJewelerShopInventory(player));
                    player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 2f, 2f);
                }
            });
        });
    }

    private static Inventory createNewJewelerShopInventory(Player player) {
        Inventory inventory = Bukkit.createInventory(player, 9, "§5Juwelier");

        inventory.setItem(0, createItemStack("", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(1, createItemStack("", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(2, createItemStack("", Material.DIAMOND));
        inventory.setItem(3, createItemStack("", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(4, createItemStack("", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(5, createItemStack("", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(6, createItemStack("", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(7, createItemStack("", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(8, createItemStack("", Material.GRAY_STAINED_GLASS_PANE));

        return inventory;
    }

    public static ItemStack createItemStack(@NotNull String itemName, @NotNull Material material) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName(itemName);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }
}
