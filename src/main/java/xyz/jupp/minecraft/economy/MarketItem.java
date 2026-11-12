package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * One catalog entry (a row of market_items). Prices are per bundle of {@code amount} items.
 * <p>
 * Buy price: {@code basePrice * e^(elasticity * demand)}, clamped to [minPrice, maxPrice]. Every bought bundle raises
 * the demand by 1, every sold bundle lowers it by 1, and the demand decays towards 0 over time.
 * Sell price: the same curve, but it may fall to {@link #SELL_FLOOR_SHARE} of the base price (a flooded good pays
 * almost nothing, for everyone: whoever dumps a farm's output ruins its price), capped at the base price and the
 * maximum price, times sellRatio. Selling therefore never profits from demand spikes, so buying somewhere and selling
 * here cannot print money.
 */
public record MarketItem(
        Material material,
        Category category,
        @Nullable String displayName,
        @Nullable String description,
        int amount,
        int basePrice,
        @Nullable Integer minPrice,
        @Nullable Integer maxPrice,
        double elasticity,
        double sellRatio,
        boolean buyable,
        boolean sellable,
        boolean core,
        int rotationWeight,
        boolean enabled,
        double demand,
        @Nullable TaxClass taxClassOverride
) {

    /** Selling pushes the price down to this share of the base price at most; buying stops at the minimum price (25 %). */
    public static final double SELL_FLOOR_SHARE = 0.05;

    /** The item's own tax class, else the default of its category. */
    public TaxClass taxClass() {
        return taxClassOverride != null ? taxClassOverride : TaxClass.forCategory(category);
    }

    public int effectiveMinPrice() {
        return minPrice != null ? minPrice : Math.max(1, (int) Math.ceil(basePrice * 0.25));
    }

    public int effectiveMaxPrice() {
        return maxPrice != null ? maxPrice : basePrice * 4;
    }

    /** Buy price of one bundle (without tax) at the given demand. */
    public int buyPriceAt(double atDemand) {
        double raw = basePrice * Math.exp(elasticity * atDemand);
        long rounded = Math.round(Math.min(Math.max(raw, effectiveMinPrice()), effectiveMaxPrice()));
        return (int) Math.max(1, rounded);
    }

    /** Sell price of one bundle at the given demand, never above basePrice * sellRatio, down to SELL_FLOOR_SHARE of it. */
    public int sellPriceAt(double atDemand) {
        double raw = basePrice * Math.exp(elasticity * atDemand);
        double floor = Math.max(1, Math.ceil(basePrice * SELL_FLOOR_SHARE));
        long price = Math.round(Math.min(Math.min(Math.max(raw, floor), effectiveMaxPrice()), basePrice));
        return (int) Math.max(0, Math.round(price * sellRatio));
    }

    public int buyPrice() {
        return buyPriceAt(demand);
    }

    public int sellPrice() {
        return sellPriceAt(demand);
    }

    /** Net total (without tax) for buying n bundles one after another, each raising the demand. */
    public int buyTotal(int bundles, double discount) {
        long total = 0;
        for (int i = 0; i < bundles; i++) total += buyPriceAt(demand + i);
        int price = (int) Math.round(total * (1.0 - discount));
        // a discount (daily offers, more of it while the state is broke) never goes below what the shop pays back
        if (discount > 0 && sellable) price = (int) Math.max(price, bundles * (Math.round(basePrice * sellRatio) + 1));
        // nor any price below what selling the same bundles right back would pay (a steep elasticity would let
        // sell-and-buy-back circles print money); with normal values this never binds
        if (sellable) {
            long back = 0;
            for (int i = 1; i <= bundles; i++) back += sellPriceAt(demand + i);
            price = (int) Math.min(Integer.MAX_VALUE, Math.max(price, back + bundles));
        }
        return price;
    }

    /** Payout for selling n bundles one after another, each lowering the demand. */
    public int sellTotal(int bundles) {
        long total = 0;
        for (int i = 0; i < bundles; i++) total += sellPriceAt(demand - i);
        return (int) total;
    }

    /** Relative change of the current buy price against the base price, e.g. 0.12 for +12 %. */
    public double trend() {
        return (double) buyPrice() / basePrice - 1.0;
    }

    public MarketItem withDemand(double newDemand) {
        return new MarketItem(material, category, displayName, description, amount, basePrice, minPrice, maxPrice,
                elasticity, sellRatio, buyable, sellable, core, rotationWeight, enabled, newDemand, taxClassOverride);
    }

}
