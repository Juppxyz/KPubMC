package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import xyz.jupp.minecraft.Main;

import java.util.List;

public class TrackerCompass extends BlackMarketItem {

    public static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "tracker_compass");
    public static final int MINUTES = 5;
    private static final List<String> BASE_LORE = List.of("§bZeigt auf den nächsten Spieler.", "§7Rechtsklick: " + MINUTES + " Minuten aktiv", "");

    public TrackerCompass() {
        super("§b§lSpürkompass", Material.COMPASS, 6000, KEY, BASE_LORE, 5);
    }

}
