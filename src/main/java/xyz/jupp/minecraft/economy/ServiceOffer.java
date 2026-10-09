package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of the "Effekte & Dienste" tab (a row of market_services).
 * Priced like the goods: basePrice * e^(elasticity * demand), between half and three times the base price;
 * every purchase raises the demand by 1 and the demand decays with the market.
 * For REPAIR the base price is the price per 100 points of durability repaired.
 */
public record ServiceOffer(
        String key,
        Kind kind,
        String displayName,
        Material icon,
        int basePrice,
        double elasticity,
        TaxClass taxClass,
        @Nullable String effect,
        int amplifier,
        int durationSeconds,
        int maxSeconds,
        int sort,
        boolean enabled,
        double demand
) {

    public enum Kind { EFFECT, REPAIR, WEATHER, DAY }

    public int priceAt(double atDemand) {
        double raw = basePrice * Math.exp(elasticity * atDemand);
        return (int) Math.max(1, Math.round(Math.min(Math.max(raw, basePrice * 0.5), basePrice * 3.0)));
    }

    public int price() {
        return priceAt(demand);
    }

    /** Net price of a repair of the given durability points (at least one base price unit). */
    public int repairPrice(int damage) {
        return (int) Math.max(price(), Math.round(price() * damage / 100.0));
    }

    public double trend() {
        return (double) price() / basePrice - 1.0;
    }

}
