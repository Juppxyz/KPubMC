package xyz.jupp.minecraft.inventory;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.utils.Text;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Small helpers for the menus that are built by slot (team menu, warp menu).
 */
public final class Items {

    private Items() {}

    /** An item with a legacy name (§ codes, not italic) and lore; no name keeps the item's own. */
    public static ItemStack named(Material material, @Nullable String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (name != null) meta.customName(Text.of(name));
        if (!lore.isEmpty()) meta.lore(Text.lore(lore));
        item.setItemMeta(meta);
        return item;
    }

    /** A filler without a tooltip. */
    public static ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack glow(ItemStack item, boolean glow) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(glow);
        item.setItemMeta(meta);
        return item;
    }

    /** 12345 -> "12.345" */
    public static String format(long amount) {
        return String.format("%,d", amount).replace(',', '.');
    }

    /** "gerade eben", "vor 5 Min", "vor 3 Std", "vor 2 Tagen" */
    public static String ago(@Nullable Instant at) {
        if (at == null) return "unbekannt";
        Duration since = Duration.between(at, Instant.now());
        if (since.toMinutes() < 1) return "gerade eben";
        if (since.toHours() < 1) return "vor " + since.toMinutes() + " Min";
        if (since.toDays() < 1) return "vor " + since.toHours() + " Std";
        long days = since.toDays();
        return "vor " + days + (days == 1 ? " Tag" : " Tagen");
    }

}
