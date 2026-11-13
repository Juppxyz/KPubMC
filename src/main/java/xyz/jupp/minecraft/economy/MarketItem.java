package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * One catalog entry (a row of market_items). Prices are per bundle of {@code amount} items.
 * <p>
 * Buy price: {@code basePrice * e^(elasticity * demand)}, clamped to [minPrice, maxPrice]. Every bought bundle raises
 * the demand by 1, every sold bundle lowers it by 1, and the demand decays towards 0 over time.
 * Sell price: the same curve down to 0; the sale that gets there saturates the good for some hours (a flooded good pays
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

    /*
     * Selling can push the buy-back down to 0; the sale that gets there saturates the good: the demand drops so far that
     * it pays nothing for the admins' saturation hours (AdminSettings, the demand decay brings it back), and the shop
     * takes none of it meanwhile. Buying stops at the minimum price (25 %).
     */

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

    /** Sell price of one bundle at the given demand, never above basePrice * sellRatio, down to 0 when flooded. */
    public int sellPriceAt(double atDemand) {
        double raw = basePrice * Math.exp(elasticity * atDemand);
        long price = Math.round(Math.min(Math.min(raw, effectiveMaxPrice()), basePrice));
        return (int) Math.max(0, Math.round(price * sellRatio));
    }

    /** The shop takes none of it right now: the next bundle would pay nothing. */
    public boolean saturated() {
        // a good that pays nothing even at a normal market is not saturated, it is simply not bought back
        return sellable && demand < 0 && sellPriceAt(demand) == 0 && sellPriceAt(0) > 0;
    }

    /** How far one sold bundle lowers the demand (the admins' factor, AdminSettings.sellStep). */
    public static double sellStep() {
        return AdminSettings.sellStep();
    }

    /** The demand after selling these bundles. */
    public double demandAfterSelling(int bundles) {
        return demand - bundles * sellStep();
    }

    /** How many of these bundles the shop takes one after another before it pays nothing for the next. */
    public int payingBundles(int max) {
        double step = sellStep();
        int paying = 0;
        while (paying < max && sellPriceAt(demand - paying * step) > 0) paying++;
        return paying;
    }

    // the highest demand at which a bundle pays nothing (the price only rises with the demand); null if it always pays
    private @Nullable Double zeroDemand() {
        if (!sellable || sellPriceAt(-1_000_000) > 0) return null;
        if (sellPriceAt(0) == 0) return 0.0;
        double low = -1_000_000, high = 0;
        for (int i = 0; i < 80; i++) {
            double middle = (low + high) / 2;
            if (sellPriceAt(middle) == 0) low = middle;
            else high = middle;
        }
        return low;
    }

    /**
     * The demand a saturating sale sets: so far below the zero line that the decay (half-life in hours) needs
     * the given hours to lift it back; the current demand if it is lower already or the good never saturates.
     */
    public double saturatedDemand(double halfLifeHours, double hours) {
        Double zero = zeroDemand();
        if (zero == null || zero >= 0 || halfLifeHours <= 0) return demand;
        return Math.min(demand, zero * Math.pow(2, hours / halfLifeHours));
    }

    /** When the shop takes it again (roughly, by the decay), null if it is not saturated. */
    public java.time.@Nullable Instant saturatedUntil(double halfLifeHours) {
        if (!saturated()) return null;
        Double zero = zeroDemand();
        if (zero == null || zero >= 0 || demand >= 0 || halfLifeHours <= 0) return java.time.Instant.now();
        double hours = halfLifeHours * Math.log(demand / zero) / Math.log(2);
        return java.time.Instant.now().plusSeconds((long) Math.max(0, hours * 3600));
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
        double step = sellStep();
        long total = 0;
        for (int i = 0; i < bundles; i++) total += sellPriceAt(demand - i * step);
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
