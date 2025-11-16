package xyz.jupp.minecraft.items;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.jupp.minecraft.Main;

import java.util.Arrays;

public class LightningSword implements Listener {

    private static LightningSword instance;

    private final ItemStack customItem;
    private final NamespacedKey swordKey;

    public LightningSword() {

        swordKey = new NamespacedKey(Main.getInstance(), "sword_of_lightning");
        customItem = new ItemStack(Material.DIAMOND_SWORD);

        ItemMeta meta = customItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "STURMSCHWERT");
            meta.setLore(Arrays.asList(ChatColor.GRAY + "Macht Blitz", ChatColor.GRAY + "und Freuer.", ChatColor.GRAY + "5 Sek Cooldown"));
            meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
            customItem.setItemMeta(meta);
        }
    }

    public ItemStack getSword() {
        return customItem;
    }

    public static LightningSword getInstance() {
        if (instance == null) {
            instance = new LightningSword();
        }
        return instance;
    }

    public NamespacedKey getSwordKey() {
        return swordKey;
    }

}
