package xyz.jupp.minecraft.utils;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import java.util.Map;

public class RedeemableItems {

    private static final Map<Material, Integer> teamPointsMaterialMap = Map.ofEntries(
            Map.entry(Material.DRAGON_EGG, 2000),
            Map.entry(Material.NETHER_STAR, 1000),
            Map.entry(Material.ENCHANTED_GOLDEN_APPLE, 350),
            Map.entry(Material.MUSIC_DISC_PIGSTEP, 250),
            Map.entry(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, 200),

            Map.entry(Material.BUDDING_AMETHYST, 120),
            Map.entry(Material.ELYTRA, 180),
            Map.entry(Material.TOTEM_OF_UNDYING, 40),
            Map.entry(Material.SHULKER_SHELL, 25),

            Map.entry(Material.SPONGE, 15),
            Map.entry(Material.HEART_OF_THE_SEA, 80),
            Map.entry(Material.NAUTILUS_SHELL, 30),
            Map.entry(Material.DISC_FRAGMENT_5, 80),
            Map.entry(Material.ECHO_SHARD, 50)
    );


    public static int getPoints(@NotNull Material material) {
        return teamPointsMaterialMap.getOrDefault(material, -1);
    }

}
