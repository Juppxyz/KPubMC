package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.BlackMarketHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PoisonBow implements CustomItemsInterface {

    private final String itemName = "§2§lCursedBow";
    private final Material itemType = Material.BOW;
    private final int minCost = 12500;

    private final NamespacedKey key = new NamespacedKey(Main.getInstance(), "poison_bow");

    private final String[] baseLore = {"§2Schießt vergiftete Pfeile ab!", " "};

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

        item.addEnchantment(Enchantment.MENDING, 1);
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
