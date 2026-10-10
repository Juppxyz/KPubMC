package xyz.jupp.minecraft.config;

import org.bukkit.Material;

import java.util.Map;
import java.util.Set;

/**
 * Limits for chest loot from the Dungeons and Taverns datapacks (config key "dntLoot").
 * A material in {@code maxPerChest} appears at most that often per chest (0 = never), an enchanted item
 * or enchanted book stays with {@code enchantedKeepChance}; everything else is untouched.
 */
public record DntLootRules(boolean enabled, Set<String> namespaces, Map<Material, Integer> maxPerChest,
                           double enchantedKeepChance) {

    // what the shop sells for a lot of money, and the resources Hondo trades in
    public static final DntLootRules DEFAULT = new DntLootRules(true, Set.of("nova_structures"), Map.ofEntries(
            Map.entry(Material.ELYTRA, 0),
            Map.entry(Material.NETHER_STAR, 0),
            Map.entry(Material.HEAVY_CORE, 0),
            Map.entry(Material.ENCHANTED_GOLDEN_APPLE, 0),
            Map.entry(Material.TOTEM_OF_UNDYING, 0),
            Map.entry(Material.TRIDENT, 0),
            Map.entry(Material.HEART_OF_THE_SEA, 0),
            Map.entry(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 0),
            Map.entry(Material.NETHERITE_INGOT, 0),
            Map.entry(Material.NETHERITE_SCRAP, 1),
            Map.entry(Material.ANCIENT_DEBRIS, 1),
            Map.entry(Material.DIAMOND_BLOCK, 0),
            Map.entry(Material.DIAMOND, 2),
            Map.entry(Material.EMERALD_BLOCK, 0),
            Map.entry(Material.EMERALD, 4),
            Map.entry(Material.GOLD_BLOCK, 0),
            Map.entry(Material.GOLD_INGOT, 4)), 0.5);
}
