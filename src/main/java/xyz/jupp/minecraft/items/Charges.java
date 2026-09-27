package xyz.jupp.minecraft.items;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;

// uses left on a black market item: stored invisibly on the item, shown as a lore line
public final class Charges {

    private static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "charges");
    private static final String LORE_PREFIX = "§7Ladungen übrig: §f";

    private Charges() {}

    // items with charges never stack, two of them would share one counter
    static void init(@NotNull ItemMeta meta, @NotNull List<String> lore, int charges) {
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.INTEGER, charges);
        meta.setMaxStackSize(1);
        lore.add(LORE_PREFIX + charges);
    }

    /** Takes one use from the item (changed in place) and returns the uses left, -1 if the item has no charges. */
    public static int use(@NotNull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Integer left = meta.getPersistentDataContainer().get(KEY, PersistentDataType.INTEGER);
        if (left == null) return -1;
        int now = Math.max(0, left - 1);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.INTEGER, now);
        List<String> lore = Text.legacyLore(meta.lore()) == null ? new ArrayList<>() : new ArrayList<>(Text.legacyLore(meta.lore()));
        lore.replaceAll(line -> line.startsWith(LORE_PREFIX) ? LORE_PREFIX + now : line);
        meta.lore(Text.lore(lore));
        item.setItemMeta(meta);
        return now;
    }

}
