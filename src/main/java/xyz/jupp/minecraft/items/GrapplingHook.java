package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import xyz.jupp.minecraft.Main;

import java.util.List;

public class GrapplingHook extends BlackMarketItem {

    public static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "grappling_hook");
    private static final List<String> BASE_LORE = List.of("§6Zieht dich zum Haken.", "§7Auswerfen, festhaken, einholen.", "");

    public GrapplingHook() {
        super("§6§lEnterhaken", Material.FISHING_ROD, 7500, KEY, BASE_LORE, 25);
    }

    // the charges wear it out, not the durability
    @Override
    public ItemStack getItemStack() {
        ItemStack item = super.getItemStack();
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

}
