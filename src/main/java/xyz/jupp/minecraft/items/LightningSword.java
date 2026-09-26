package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.jupp.minecraft.Main;

import java.util.Arrays;

// only the item for /customItem sword, it has no effect since its listener was unregistered
public class LightningSword {

    private final ItemStack customItem;

    public LightningSword() {
        NamespacedKey swordKey = new NamespacedKey(Main.getInstance(), "sword_of_lightning");
        customItem = new ItemStack(Material.DIAMOND_SWORD);

        ItemMeta meta = customItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bSTURMSCHWERT");
            meta.setLore(Arrays.asList("§7Macht Blitz", "§7und Freuer.", "§75 Sek Cooldown"));
            meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
            customItem.setItemMeta(meta);
        }
    }

    public ItemStack getSword() {
        return customItem;
    }

}
