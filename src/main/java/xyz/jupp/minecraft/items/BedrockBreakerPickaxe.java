package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import xyz.jupp.minecraft.Main;

import java.util.List;

public class BedrockBreakerPickaxe extends BlackMarketItem {

    public static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "bedrock_breaker_pickaxe");

    private static final List<String> BASE_LORE = List.of("§8Ein wahrer Brecher.", "", "§7§oNur einmal nutzbar!");

    public BedrockBreakerPickaxe() {
        super("§8Bedrock-§7§lBreaker", Material.WOODEN_PICKAXE, 15000, KEY, BASE_LORE);
    }

}
