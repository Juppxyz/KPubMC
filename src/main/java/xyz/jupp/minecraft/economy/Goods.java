package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;

/**
 * Goods from a trader (shop, Hondo, emergency sale) carry an invisible mark: Nomad only takes what players gathered
 * themselves, so team points cannot be bought. The shop and Hondo take marked goods back like plain ones, and a
 * give-back after a failed trade hands out exactly what was taken. Crafting, smelting or placing and mining again
 * gives plain items (the game does not carry the mark over).
 */
public final class Goods {

    private Goods() {}

    private static volatile NamespacedKey key;

    /** What was taken for a trade, split into plain and bought items, so a give-back restores both. */
    public record Taken(Material material, int plain, int bought) {}

    private static NamespacedKey key() {
        NamespacedKey current = key;
        if (current == null) {
            current = new NamespacedKey(Main.getInstance(), "bought");
            key = current;
        }
        return current;
    }

    /** A stack as a trader hands it out: bought goods carry the mark. */
    public static ItemStack stack(@NotNull Material material, int amount, boolean bought) {
        ItemStack item = new ItemStack(material, amount);
        if (!bought) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** The mark's key, e.g. to tell it apart from the custom items' keys in the same namespace. */
    public static boolean isMark(@NotNull NamespacedKey candidate) {
        return candidate.equals(key());
    }

    public static boolean isBought(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key(), PersistentDataType.BYTE);
    }

}
