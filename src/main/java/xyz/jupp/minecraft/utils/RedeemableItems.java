package xyz.jupp.minecraft.utils;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import java.util.Map;

public class RedeemableItems {

    private static final Map<String, Integer> teamPointsMaterialMap = Map.ofEntries(
            Map.entry("DRAGON_EGG", 2000),
            Map.entry("NETHER_STAR", 1000),
            Map.entry("ENCHANTED_GOLDEN_APPLE", 350),
            Map.entry("MUSIC_DISC_PIGSTEP", 250),
            Map.entry("SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE", 200),

            Map.entry("BUDDING_AMETHYST", 120),
            Map.entry("ELYTRA", 180),
            Map.entry("TOTEM_OF_UNDYING", 40),
            Map.entry("SHULKER_SHELL", 25),

            Map.entry("SPONGE", 15),
            Map.entry("HEART_OF_THE_SEA", 80),
            Map.entry("NAUTILUS_SHELL", 30),
            Map.entry("DISC_FRAGMENT_5", 80),
            Map.entry("ECHO_SHARD", 50)
    );


    public static int getPoints(@NotNull Material material) {
        if (teamPointsMaterialMap.containsKey(material.name())) {
            return teamPointsMaterialMap.get(material.name());
        }
        return -1;
    }

}
