package xyz.jupp.minecraft.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ItemStackUtil {

    public static ItemStack createItemStack(@NotNull String itemName, @NotNull Material material) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName(itemName);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack createItemStack(@NotNull String itemName, @NotNull Material material, @NotNull String[] lores) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName(itemName);
        if (lores != null) itemMeta.setLore(List.of(lores));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

}
