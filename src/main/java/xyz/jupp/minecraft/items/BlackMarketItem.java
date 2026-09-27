package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;

// shared build of the black market items: PDC marker, name, lore and the current price as the last lore line
abstract class BlackMarketItem implements CustomItemsInterface {

    private final String itemName;
    private final Material itemType;
    private final int minCost;
    private final NamespacedKey key;
    private final List<String> baseLore;

    BlackMarketItem(String itemName, Material itemType, int minCost, NamespacedKey key, List<String> baseLore) {
        this.itemName = itemName;
        this.itemType = itemType;
        this.minCost = minCost;
        this.key = key;
        this.baseLore = baseLore;
    }

    @Override
    public ItemStack getItemStack() {
        ItemStack item = new ItemStack(itemType);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.customName(Text.of(itemName));

        List<String> lore = new ArrayList<>(baseLore);
        meta.lore(Text.lore(lore));

        item.setItemMeta(meta);
        return item;
    }

    @Override
    public int getMinCost() {
        return minCost;
    }

    public String getItemName() {
        return itemName;
    }

    public NamespacedKey getKey() {
        return key;
    }

}
