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


public class Flamethrower implements CustomItemsInterface {

    private final String itemName = "§c§lFlammenwerfer";
    private final Material itemType = Material.BLAZE_ROD;
    private final int minCost = 5000;

    private final NamespacedKey key = new NamespacedKey(Main.getInstance(), "flamethrower_sword");

    private final String[] baseLore = {"§cErzeugt einen explosiven Feuerball!", "", "§7§oNur einmal nutzbar!"};

    public ItemStack getItemStack(){
        ItemStack item = new ItemStack(itemType);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.setDisplayName(itemName);

        List<String> lore = new ArrayList<>(Arrays.asList(baseLore));
        lore.add("§fPreis: " + Main.getCurrencyName(BlackMarketHandler.getCurrentCosts().get()));
        meta.setLore(lore);

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    public Material getItemType() {
        return itemType;
    }

    public int getMinCost() {
        return minCost;
    }

    public String getItemName() {
        return itemName;
    }
}
