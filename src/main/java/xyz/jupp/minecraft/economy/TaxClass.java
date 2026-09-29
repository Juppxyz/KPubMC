package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Tax classes of purchases; the base rates come from the config, the economy factor scales them. */
public enum TaxClass {

    BASIC("Grundbedarf", 0.10),
    STANDARD("Standard", 0.20),
    LUXURY("Luxus", 0.35);

    private final String label;
    private final double defaultRate;

    TaxClass(String label, double defaultRate) {
        this.label = label;
        this.defaultRate = defaultRate;
    }

    public String label() {
        return label;
    }

    public double defaultRate() {
        return defaultRate;
    }

    public static TaxClass forCategory(Category category) {
        return switch (category) {
            case FOOD, FARMING -> BASIC;
            case RARE -> LUXURY;
            default -> STANDARD;
        };
    }

    public static @Nullable TaxClass parse(@Nullable String name) {
        if (name == null) return null;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "basic", "grundbedarf" -> BASIC;
            case "standard" -> STANDARD;
            case "luxury", "luxus" -> LUXURY;
            default -> null;
        };
    }

}
