package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import xyz.jupp.minecraft.Main;

import java.util.List;

public class KeepInventoryItem extends BlackMarketItem {

    public static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "keepinventory_chest");

    private static final List<String> BASE_LORE = List.of("§eDiese kleine Wunderbox sichert", "§edeine Items und Level", " ", "§7§oNur einmal nutzbar!");

    public KeepInventoryItem() {
        super("§6Box of Undying", Material.CHEST, 5000, KEY, BASE_LORE);
    }

}
