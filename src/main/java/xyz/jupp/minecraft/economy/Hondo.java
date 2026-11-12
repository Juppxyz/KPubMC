package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Hondo, the jeweler: sells jewels and rewards regular customers (no exchanges since 2026-11-12, the owner removed
 * them: they gave too little for the effort).
 * <p>
 * Selling: the shop's price curve per piece (dearer the more is bought), never below {@link #PRICE_FLOOR} of the base
 * price and never below the most the shop can ever pay for it (plus a margin), so buying here and selling in the
 * shop never pays off. Netherite at most {@link #NETHERITE_PER_DAY} pieces per player and day.
 * The friendship bonus only lowers the sale price (discount), the floors always hold.
 */
public final class Hondo {

    private Hondo() {}

    public static final String PREFIX = Main.getJewelerVillagerName() + " §7» §f";

    /* goods and friendship offers */

    public static final List<Material> GOODS = List.of(Material.EMERALD, Material.GOLD_INGOT, Material.DIAMOND,
            Material.NETHERITE_INGOT, Material.AMETHYST_SHARD, Material.RESIN_CLUMP, Material.LAPIS_LAZULI);

    /** Netherite a player gets from Hondo per day (bought or as a friendship offer). */
    public static final int NETHERITE_PER_DAY = 2;
    /** Hondo never sells below this share of the base price, however many were sold to the shop. */
    static final double PRICE_FLOOR = 0.8;

    /**
     * Once per player and level (also after losing and regaining the level): unlocked by the friendship level,
     * priced from the shop with a big discount and tax free. No lasting offers just for a high level.
     */
    public record FriendOffer(int level, Material material, int amount, double discount) {}

    // netherite is the big one at the end: enough for a whole armour
    public static final List<FriendOffer> OFFERS = List.of(
            new FriendOffer(1, Material.EMERALD, 32, 0.50),
            new FriendOffer(2, Material.DIAMOND, 8, 0.40),
            new FriendOffer(3, Material.TOTEM_OF_UNDYING, 1, 0.50),
            new FriendOffer(4, Material.ENCHANTED_GOLDEN_APPLE, 1, 0.60),
            new FriendOffer(5, Material.NETHERITE_INGOT, 2, 0.50));

    private static final Map<Material, String> LABELS = Map.ofEntries(
            Map.entry(Material.EMERALD, "§aSmaragd"),
            Map.entry(Material.GOLD_INGOT, "§eGoldbarren"),
            Map.entry(Material.DIAMOND, "§bDiamant"),
            Map.entry(Material.NETHERITE_INGOT, "§7Netheritbarren"),
            Map.entry(Material.AMETHYST_SHARD, "§dAmethystsplitter"),
            Map.entry(Material.RESIN_CLUMP, "§6Harzklumpen"),
            Map.entry(Material.LAPIS_LAZULI, "§9Lapislazuli"),
            Map.entry(Material.TOTEM_OF_UNDYING, "§eTotem der Unsterblichkeit"),
            Map.entry(Material.ENCHANTED_GOLDEN_APPLE, "§dVerzauberter Goldener Apfel"));

    static final TaxClass TAX_CLASS = TaxClass.LUXURY;
    // Hondo's lowest price lies this much above the most the shop could ever pay
    static final double FLOOR_MARGIN = 1.1;
    static final double DISCOUNT_STEP = 0.02;
    public static final int BULK = 8;

    /** Pieces a right click buys: BULK, netherite only up to its daily limit. */
    public static int bulk(@NotNull Material material) {
        return material == Material.NETHERITE_INGOT ? Math.min(BULK, NETHERITE_PER_DAY) : BULK;
    }


    /* friendship: points from trades, levels with a cap, a slow decay without trades */

    public static final int MAX_LEVEL = 5;
    private static final String[] LEVEL_NAMES = {"Fremder", "Bekannter", "Kunde", "Stammkunde", "Freund", "Vertrauter"};
    private static final double[] LEVEL_POINTS = {0, 25, 80, 200, 400, 700};
    static final double SCHILLING_PER_POINT = 50;
    // the first trades of a day give an extra point each
    static final int BONUS_TRADES_PER_DAY = 5;
    // a day counts as active with this many trades or this much volume, otherwise the points shrink over night
    public static final int ACTIVE_TRADES = 2;
    public static final int ACTIVE_VOLUME = 500;
    static final double DECAY_SHARE = 0.08;
    static final double DECAY_MIN = 2;
    // the capped points stay this far below the level after next
    private static final double DAY_CAP_GAP = 0.01;

    public static int level(double points) {
        for (int level = MAX_LEVEL; level > 0; level--) {
            if (points >= LEVEL_POINTS[level]) return level;
        }
        return 0;
    }

    public static String levelName(int level) {
        return LEVEL_NAMES[Math.max(0, Math.min(MAX_LEVEL, level))];
    }

    /** Points needed for the level, 0 for level 0. */
    public static double pointsFor(int level) {
        return LEVEL_POINTS[Math.max(0, Math.min(MAX_LEVEL, level))];
    }

    public static double discount(int level) {
        return level * DISCOUNT_STEP;
    }

    static double pointsForTrade(int value, int tradesToday) {
        return value / SCHILLING_PER_POINT + (tradesToday < BONUS_TRADES_PER_DAY ? 1 : 0);
    }

    /**
     * At most one level up per day: the points stop just below the level after next (counted from the level at the
     * start of the day), everything above is lost. Returns the points the trade really gives.
     */
    static double cappedGain(double points, double gain, int levelAtDayStart) {
        if (levelAtDayStart + 2 > MAX_LEVEL) return gain;
        double limit = LEVEL_POINTS[levelAtDayStart + 2] - DAY_CAP_GAP;
        return Math.max(0, Math.min(gain, limit - points));
    }

    static double decayed(double points) {
        return Math.max(0, points - Math.max(DECAY_MIN, points * DECAY_SHARE));
    }


    /* prices (pure, callable from any thread with a catalog row) */

    /**
     * Net price of pieces bought at Hondo: the shop's buy price per piece minus the discount, never below PRICE_FLOOR of
     * the base price nor below the most the shop could pay.
     */
    public static int salePrice(@NotNull MarketItem item, int units, double discount) {
        double raw = 0;
        for (int i = 0; i < units; i++) raw += item.buyPriceAt(item.demand() + (double) i / item.amount());
        int price = (int) Math.ceil(raw / item.amount() * (1.0 - discount) - 1e-9);
        int normalFloor = (int) Math.ceil(units * (double) item.basePrice() / item.amount() * PRICE_FLOOR - 1e-9);
        return Math.max(Math.max(Math.max(1, price), normalFloor), floor(item, units));
    }

    // above the most the shop can ever pay for these pieces: never more than base price * sell ratio per bundle,
    // rounded like MarketItem.sellPriceAt
    static int floor(@NotNull MarketItem item, int units) {
        if (!item.sellable()) return 0;
        long maxPerBundle = Math.round(item.basePrice() * item.sellRatio());
        return (int) Math.ceil(units * (double) maxPerBundle * FLOOR_MARGIN / item.amount());
    }

    /** Net price of a friendship offer; it does not move the market. */
    public static int offerPrice(@NotNull MarketItem item, @NotNull FriendOffer offer) {
        int price = (int) Math.ceil(offer.amount() * (double) item.buyPrice() / item.amount() * (1.0 - offer.discount()) - 1e-9);
        return Math.max(Math.max(1, price), floor(item, offer.amount()));
    }

    public static @Nullable FriendOffer offer(int level) {
        for (FriendOffer offer : OFFERS) {
            if (offer.level() == level) return offer;
        }
        return null;
    }

    public static String label(@NotNull Material material) {
        String label = LABELS.get(material);
        return label != null ? label : "§f" + material.name().toLowerCase().replace('_', ' ');
    }


    /* daily decay (worker thread, from Market.dailyUpdate) */

    // the last finished day whose activity was checked
    private static volatile @Nullable LocalDate evaluatedDay;

    /** Blocking, called in onEnable. */
    public static void load() {
        evaluatedDay = HondoRepository.evaluatedDay();
    }

    public static boolean isUpToDate(@NotNull LocalDate today) {
        LocalDate known = evaluatedDay;
        return known != null && !known.isBefore(today.minusDays(1));
    }

    /** Shrinks the points of everyone who did not trade enough on the last finished day (each day only once). */
    public static void dailyUpdate(@NotNull LocalDate today) {
        if (isUpToDate(today)) return;
        LocalDate last = today.minusDays(1);
        int changed = HondoRepository.decay(last);
        evaluatedDay = last;
        Main.getInstance().getSLF4JLogger().info("Hondo friendship: evaluated up to {}, {} players lost points", last, changed);
    }

}
