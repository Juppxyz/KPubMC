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
 * The End opens some days after a season start ("endUnlock" in the config, Europe/Berlin; empty = open).
 * Until then the End portal is locked and End-only items are not offered or requested anywhere.
 */
public final class EndAccess {

    private EndAccess() {}

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private static final Set<Material> END_ITEMS = EnumSet.of(
            Material.END_STONE, Material.END_STONE_BRICKS, Material.PURPUR_BLOCK, Material.PURPUR_PILLAR,
            Material.CHORUS_FRUIT, Material.POPPED_CHORUS_FRUIT, Material.CHORUS_FLOWER, Material.END_ROD,
            Material.SHULKER_SHELL, Material.SHULKER_BOX, Material.ELYTRA, Material.DRAGON_BREATH,
            Material.DRAGON_EGG, Material.DRAGON_HEAD, Material.END_CRYSTAL);

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

    /** False for End-only items while the End is still locked. */
    public static boolean isAvailable(@NotNull Material material) {
        return !isEndItem(material) || isOpen();
    }

}
