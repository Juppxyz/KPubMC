package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import xyz.jupp.minecraft.Main;

import java.util.List;

public class ForgedPapers extends BlackMarketItem {

    public static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "forged_papers");
    private static final List<String> BASE_LORE = List.of("§fNeue Identität, neues Glück.", "§7Rechtsklick: Die Fahndung nach dir endet.", "", "§7§oNur einmal nutzbar!");

    public ForgedPapers() {
        super("§f§lGefälschte Papiere", Material.PAPER, 10000, KEY, BASE_LORE);
    }

}
