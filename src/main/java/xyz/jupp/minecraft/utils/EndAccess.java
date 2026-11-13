package xyz.jupp.minecraft.utils;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.config.ConfigManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;

/**
 * The Nether and the End open some days after a season start ("netherUnlock" and "endUnlock" in the config,
 * Europe/Berlin; empty = open). Until then their portals are locked and their items are not offered or requested
 * anywhere (shop, daily offers, random item, Nomad, Hondo, the AI review).
 */
public final class EndAccess {

    private EndAccess() {}

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private static final Set<Material> END_ITEMS = EnumSet.of(
            Material.END_STONE, Material.END_STONE_BRICKS, Material.PURPUR_BLOCK, Material.PURPUR_PILLAR,
            Material.CHORUS_FRUIT, Material.POPPED_CHORUS_FRUIT, Material.CHORUS_FLOWER, Material.END_ROD,
            Material.SHULKER_SHELL, Material.SHULKER_BOX, Material.ELYTRA, Material.DRAGON_BREATH,
            Material.DRAGON_EGG, Material.DRAGON_HEAD, Material.END_CRYSTAL);

    // goods only the Nether gives (a bastion's template, the Wither's star and skull included)
    private static final Set<Material> NETHER_ITEMS = EnumSet.of(
            Material.NETHERRACK, Material.NETHER_BRICK, Material.NETHER_BRICKS, Material.NETHER_WART, Material.NETHER_WART_BLOCK,
            Material.QUARTZ, Material.QUARTZ_BLOCK, Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE,
            Material.GLOWSTONE, Material.GLOWSTONE_DUST, Material.SOUL_SAND, Material.SOUL_SOIL,
            Material.BLAZE_ROD, Material.BLAZE_POWDER, Material.GHAST_TEAR, Material.MAGMA_CREAM,
            Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP, Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK,
            Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Material.BASALT, Material.BLACKSTONE, Material.GILDED_BLACKSTONE,
            Material.CRIMSON_STEM, Material.WARPED_STEM, Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM,
            Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS, Material.SHROOMLIGHT,
            Material.WITHER_SKELETON_SKULL, Material.NETHER_STAR);

    public static @Nullable LocalDateTime unlock() {
        return ConfigManager.getManager().getEndUnlock();
    }

    public static boolean isOpen() {
        LocalDateTime unlock = unlock();
        return unlock == null || !LocalDateTime.now(ZONE).isBefore(unlock);
    }

    public static boolean isEndItem(@NotNull Material material) {
        return END_ITEMS.contains(material);
    }

    public static @Nullable LocalDateTime netherUnlock() {
        return ConfigManager.getManager().getNetherUnlock();
    }

    public static boolean isNetherOpen() {
        LocalDateTime unlock = netherUnlock();
        return unlock == null || !LocalDateTime.now(ZONE).isBefore(unlock);
    }

    public static boolean isNetherItem(@NotNull Material material) {
        return NETHER_ITEMS.contains(material);
    }

    /** False for End-only and Nether-only items while that dimension is still locked. */
    public static boolean isAvailable(@NotNull Material material) {
        return (!isEndItem(material) || isOpen()) && (!isNetherItem(material) || isNetherOpen());
    }

}
