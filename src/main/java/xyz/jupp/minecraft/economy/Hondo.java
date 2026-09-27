package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Hondo, the jeweler: sells jewels at the shop's prices, exchanges items and rewards regular customers.
 * <p>
 * The shop buys the same items back, so every price follows two rules against money glitches:
 * <ul>
 *   <li>Hondo never sells below the most the shop can ever pay for an item (base price * sell ratio, plus a margin).
 *       Buying here and selling in the shop never pays off, also not after the demand has recovered.</li>
 *   <li>An exchange values the player's items at the shop's current buy-back price and Hondo's items at his selling
 *       price, minus a fee. Only items the shop buys back are accepted, so an exchange opens no new way to money
 *       (emeralds from villagers stay worthless for the shop).</li>
 * </ul>
 * The friendship bonus only lowers the discount side (sale discount, exchange fee), the floors always hold.
 */
public final class Hondo {

    private Hondo() {}

    public static final String PREFIX = Main.getJewelerVillagerName() + " §7» §f";

    /* goods, exchanges and friendship offers */

    public static final List<Material> GOODS = List.of(Material.EMERALD, Material.GOLD_INGOT, Material.DIAMOND,
            Material.NETHERITE_INGOT, Material.AMETHYST_SHARD, Material.RESIN_CLUMP, Material.LAPIS_LAZULI);

    /** The player gives pieces of {@code give} (computed from the prices) and gets {@code amount} pieces of {@code get}. */
    public record Exchange(Material give, Material get, int amount) {}

    public static final List<Exchange> EXCHANGES = List.of(
            new Exchange(Material.GOLD_INGOT, Material.EMERALD, 8),
            new Exchange(Material.DIAMOND, Material.EMERALD, 16),
            new Exchange(Material.IRON_INGOT, Material.GOLD_INGOT, 4),
            new Exchange(Material.GOLD_INGOT, Material.DIAMOND, 1),
            new Exchange(Material.DIAMOND, Material.NETHERITE_INGOT, 1),
            new Exchange(Material.LAPIS_LAZULI, Material.AMETHYST_SHARD, 4),
            new Exchange(Material.AMETHYST_SHARD, Material.RESIN_CLUMP, 2));

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
            new FriendOffer(5, Material.NETHERITE_INGOT, 4, 0.50));

    private static final Map<Material, String> LABELS = Map.ofEntries(
            Map.entry(Material.EMERALD, "§aSmaragd"),
            Map.entry(Material.GOLD_INGOT, "§eGoldbarren"),
            Map.entry(Material.DIAMOND, "§bDiamant"),
            Map.entry(Material.NETHERITE_INGOT, "§8Netheritbarren"),
            Map.entry(Material.AMETHYST_SHARD, "§dAmethystsplitter"),
            Map.entry(Material.RESIN_CLUMP, "§6Harzklumpen"),
            Map.entry(Material.LAPIS_LAZULI, "§9Lapislazuli"),
            Map.entry(Material.IRON_INGOT, "§7Eisenbarren"),
            Map.entry(Material.TOTEM_OF_UNDYING, "§eTotem der Unsterblichkeit"),
            Map.entry(Material.ENCHANTED_GOLDEN_APPLE, "§dVerzauberter Goldener Apfel"));

    static final TaxClass TAX_CLASS = TaxClass.LUXURY;
    // Hondo's lowest price lies this much above the most the shop could ever pay
    static final double FLOOR_MARGIN = 1.1;
    static final double BASE_FEE = 0.15;
    static final double FEE_STEP = 0.02;
    static final double DISCOUNT_STEP = 0.02;
    // an exchange asks for at most 9 stacks
    static final int MAX_EXCHANGE_INPUT = 576;
    public static final int BULK = 8;


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

    public static double fee(int level) {
        return BASE_FEE - level * FEE_STEP;
    }

    static double pointsForTrade(int value, int tradesToday) {
        return value / SCHILLING_PER_POINT + (tradesToday < BONUS_TRADES_PER_DAY ? 1 : 0);
    }

    static double decayed(double points) {
        return Math.max(0, points - Math.max(DECAY_MIN, points * DECAY_SHARE));
    }


    /* prices (pure, callable from any thread with a catalog row) */

    /** Net price of pieces bought at Hondo: the shop's buy price per piece minus the discount, never below the floor. */
    public static int salePrice(@NotNull MarketItem item, int units, double discount) {
        double raw = 0;
        for (int i = 0; i < units; i++) raw += item.buyPriceAt(item.demand() + (double) i / item.amount());
        int price = (int) Math.ceil(raw / item.amount() * (1.0 - discount) - 1e-9);
        return Math.max(Math.max(1, price), floor(item, units));
    }

    // above the most the shop can ever pay for these pieces: never more than base price * sell ratio per bundle,
    // rounded like MarketItem.sellPriceAt
    static int floor(@NotNull MarketItem item, int units) {
        if (!item.sellable()) return 0;
        long maxPerBundle = Math.round(item.basePrice() * item.sellRatio());
        return (int) Math.ceil(units * (double) maxPerBundle * FLOOR_MARGIN / item.amount());
    }

    /** What the shop would pay right now for these pieces; each piece lowers the price a little, like selling does. */
    public static int buyBackValue(@NotNull MarketItem item, int units) {
        double value = 0;
        for (int i = 1; i <= units; i++) value += item.sellPriceAt(item.demand() - (double) i / item.amount());
        return (int) Math.floor(value / item.amount());
    }

    /** Pieces of {@code give} the exchange needs, 0 if it is not possible right now. */
    public static int exchangeInput(@NotNull MarketItem give, @NotNull MarketItem get, int amount, double fee) {
        if (!give.enabled() || !give.sellable() || !get.enabled()) return 0;
        int cost = salePrice(get, amount, 0);
        double value = 0;
        for (int units = 1; units <= MAX_EXCHANGE_INPUT; units++) {
            value += give.sellPriceAt(give.demand() - (double) units / give.amount());
            if (Math.floor(value / give.amount()) * (1.0 - fee) >= cost) return units;
        }
        return 0;
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
