package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/** Shop tabs of the catalog items (the daily offers have their own tab). */
public enum Category {

    FARMING("§aFarm", Material.WHEAT),
    BLOCKS("§6Blöcke", Material.BRICKS),
    ORES("§bRohstoffe", Material.DIAMOND),
    FOOD("§eNahrung", Material.COOKED_BEEF),
    RARE("§dSelten", Material.NETHER_STAR),
    MISC("§7Sonstiges", Material.CHEST);

    private final String label;
    private final Material icon;

    Category(String label, Material icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public Material icon() {
        return icon;
    }

    public static @Nullable Category parse(String name) {
        for (Category category : values()) {
            if (category.name().equalsIgnoreCase(name)) return category;
        }
        return null;
    }

}
