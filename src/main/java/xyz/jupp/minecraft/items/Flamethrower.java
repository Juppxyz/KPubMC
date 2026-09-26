package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import xyz.jupp.minecraft.Main;

import java.util.List;

public class Flamethrower extends BlackMarketItem {

    public static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "flamethrower_sword");

    private static final List<String> BASE_LORE = List.of("§cErzeugt einen explosiven Feuerball!", "", "§7§oNur einmal nutzbar!");

    public Flamethrower() {
        super("§c§lFlammenwerfer", Material.BLAZE_ROD, 5000, KEY, BASE_LORE);
    }

}
