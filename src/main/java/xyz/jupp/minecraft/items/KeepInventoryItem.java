package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.BlackMarketHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class KeepInventoryItem implements CustomItemsInterface {


    private final String itemName = "§6Box of Undying";
    private final Material itemType = Material.CHEST;
    private final int minCost = 5000;

    private final NamespacedKey key = new NamespacedKey(Main.getInstance(), "keepinventory_chest");

    private final String[] baseLore = {"§eDiese kleine Wunderbox sichert", "§edeine Items und Level", " ", "§7§oNur einmal nutzbar!"};

    public ItemStack getItemStack(){
        ItemStack item = new ItemStack(itemType);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.setDisplayName(itemName);

        List<String> lore = new ArrayList<>(Arrays.asList(baseLore));
        lore.add("§fPreis: " + Main.getCurrencyName(BlackMarketHandler.getCurrentCosts().get()));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    public int getMinCost() {
        return minCost;
    }

    public Material getItemType() {
        return itemType;
    }

    public String getItemName() {
        return itemName;
    }

    public NamespacedKey getKey() {
        return key;
    }



}
