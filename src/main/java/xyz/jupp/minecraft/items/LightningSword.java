package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Text;

import java.util.List;

// only the item for /customItem sword, it has no effect since its listener was unregistered
public class LightningSword {

    private static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "sword_of_lightning");

    private final ItemStack customItem;

    public LightningSword() {
        customItem = new ItemStack(Material.DIAMOND_SWORD);

        ItemMeta meta = customItem.getItemMeta();
        meta.customName(Text.of("§bSTURMSCHWERT"));
        meta.lore(Text.lore(List.of("§7Macht Blitz", "§7und Freuer.", "§75 Sek Cooldown")));
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        customItem.setItemMeta(meta);
    }

    public ItemStack getSword() {
        return customItem;
    }

}
