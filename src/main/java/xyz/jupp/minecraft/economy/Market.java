package xyz.jupp.minecraft.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.utils.EndAccess;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.utils.TabListUtil;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * The market: catalog cache for the GUI (main thread reads, no database), daily offers and the demand decay.
 * The database stays the source of truth; every trade returns the fresh catalog row and updates the cache.
 */
public final class Market {

    private Market() {}

    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private static final long DECAY_INTERVAL_MINUTES = 10;
    private static final String DECAYED_AT_KEY = "demand_decayed_at";
    private static volatile java.time.@Nullable Instant lastDecay;
    // the random item costs this share of the average daily offer
    private static final double RANDOM_ITEM_SHARE = 0.8;

    private static final Map<Material, MarketItem> items = new ConcurrentHashMap<>();
    // purchases per player and item within an hour, in bundles: a safety net where the price reaction is not enough;
    // building blocks get more, rare items less; /shopadmin set <item> limit overrides it
    private static final int HOURLY_LIMIT_BLOCKS = 64;
    private static final int HOURLY_LIMIT_RARE = 2;
    private static final int HOURLY_LIMIT_DEFAULT = 16;
    private static volatile Map<Material, Integer> hourlyLimits = Map.of();
    private static volatile List<Material> dailyOffers = List.of();
    // the start catalog as shipped (market-catalog.json), whatever the database has made of it since
    private static volatile Map<Material, MarketItem> seeds = Map.of();
    private static volatile LocalDate offersDay = null;
    private static final AtomicBoolean updating = new AtomicBoolean();


    /* lifecycle (blocking parts run in onEnable or on workers) */

    /**
     * What the shop pays back for farmable and renewable goods, far below a diamond per piece (2026-11-12: lapis,
     * amethyst, resin and gold paid as much as or more than diamonds). market-catalog.json has the same values; an
     * older database gets them once.
     */
    static final Map<Material, Double> SELL_RATIOS_2026_11 = Map.ofEntries(Map.entry(Material.RESIN_CLUMP, 0.03), Map.entry(Material.AMETHYST_SHARD, 0.015), Map.entry(Material.GOLD_INGOT, 0.08), Map.entry(Material.LAPIS_LAZULI, 0.06), Map.entry(Material.GHAST_TEAR, 0.2), Map.entry(Material.PITCHER_POD, 0.03), Map.entry(Material.RABBIT_FOOT, 0.1), Map.entry(Material.PHANTOM_MEMBRANE, 0.17), Map.entry(Material.ENDER_PEARL, 0.17), Map.entry(Material.BLAZE_ROD, 0.2), Map.entry(Material.PUFFERFISH, 0.1), Map.entry(Material.GLOW_INK_SAC, 0.15), Map.entry(Material.TROPICAL_FISH, 0.13), Map.entry(Material.SLIME_BALL, 0.2), Map.entry(Material.HONEY_BOTTLE, 0.27), Map.entry(Material.HONEYCOMB, 0.24), Map.entry(Material.IRON_INGOT, 0.15), Map.entry(Material.GUNPOWDER, 0.2));

    public static void load() {
        int eased = MarketRepository.easeElasticityOnce(0.75);
        if (eased > 0) Main.getInstance().getSLF4JLogger().info("price reaction of {} goods eased (elasticity x0.75)", eased);
        int lowered = MarketRepository.setSellRatiosOnce("sell_ratios_2026_11", SELL_RATIOS_2026_11);
        if (lowered > 0) Main.getInstance().getSLF4JLogger().info("shop buy-back of {} farmable goods lowered", lowered);
        seedCatalog();
        reload();
        LocalDate today = LocalDate.now(ZONE);
        List<Material> stored = MarketRepository.loadRotation(today);
        if (stored.isEmpty()) {
            rotate();
        } else {
            dailyOffers = stored;
            offersDay = today;
        }
    }

    public static void reload() {
        Map<Material, MarketItem> loaded = new ConcurrentHashMap<>();
        for (MarketItem item : MarketRepository.loadAll()) loaded.put(item.material(), item);
        items.keySet().retainAll(loaded.keySet());
        items.putAll(loaded);
        hourlyLimits = MarketRepository.hourlyLimits();
    }

    /** Bundles a player may buy of this item within an hour (shop, random item and Hondo together). */
    public static int hourlyLimit(@NotNull MarketItem item) {
        Integer override = hourlyLimits.get(item.material());
        if (override != null) return override;
        return switch (item.category()) {
            case BLOCKS -> HOURLY_LIMIT_BLOCKS;
            case RARE -> HOURLY_LIMIT_RARE;
            default -> HOURLY_LIMIT_DEFAULT;
        };
    }

    /** The same in pieces. */
    public static int hourlyLimitItems(@NotNull MarketItem item) {
        return hourlyLimit(item) * item.amount();
    }

    /** The limit set with /shopadmin, null for the category default. */
    public static @Nullable Integer hourlyLimitOverride(@NotNull Material material) {
        return hourlyLimits.get(material);
    }

    public static void startTasks() {
        long period = 20L * 60 * DECAY_INTERVAL_MINUTES;
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), Market::decay, period, period);
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), Market::dailyUpdate, 20L * 30, 20L * 60);
    }

    // the demand halves every half-life of real time: the timer counts ticks, which run slow while the server lags
    private static void decay() {
        double halfLifeHours = ConfigManager.getManager().getDemandHalfLifeHours();
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant last = lastDecay;
        if (last == null) {
            String stored = MarketRepository.meta(DECAYED_AT_KEY);
            last = stored == null ? now.minus(java.time.Duration.ofMinutes(DECAY_INTERVAL_MINUTES)) : java.time.Instant.parse(stored);
        }
        // a stop of more than a week counts as a week, the market has calmed down by then anyway
        double minutes = Math.min(7 * 24 * 60, Math.max(0, java.time.Duration.between(last, now).toMillis() / 60_000.0));
        lastDecay = now;
        MarketRepository.setMeta(DECAYED_AT_KEY, now.toString());
        double factor = Math.pow(0.5, minutes / (60.0 * halfLifeHours));
        MarketRepository.decayDemand(factor);
        Services.decayDemand(factor);
        reload();
        Services.reload();
        Tasks.sync(ShopView::refreshAll);
        checkState();
    }

    // is the state broke or has it recovered, and can it pay bonds back
    private static void checkState() {
        Component announcement;
        List<Bonds.Repaid> repaid;
        try {
            announcement = Bankruptcy.check();
            repaid = Bonds.repayDue();
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().warn("State check failed: {}", e.toString());
            return;
        }
        if (announcement == null && repaid.isEmpty()) return;
        Tasks.sync(() -> {
            if (announcement != null) {
                Bukkit.broadcast(announcement);
                TabListUtil.updateTabForAll();
                ShopView.refreshAll();
            }
            repaid.forEach(Bank::announceBond);
        });
    }

    /**
     * The daily update (worker thread, checked every minute): measure the economy and set the tax factor, draw the new
     * daily offers and announce them, then let the AI review the catalog (it can name the offers, and nothing waits for
     * it; a failed review is tried again later the same day). Each step runs once per day, also after a restart.
     */
    private static void dailyUpdate() {
        LocalDate today = LocalDate.now(ZONE);
        if (today.equals(offersDay) && Economy.isMeasured(today) && Nomad.isUpToDate(today) && Hondo.isUpToDate(today)
                && !MarketReview.due(today)) return;
        if (!updating.compareAndSet(false, true)) return;
        try {
            Economy.Snapshot snapshot = null;
            if (!Economy.isMeasured(today)) {
                try {
                    snapshot = Economy.measure(today);
                    reload();
                } catch (RuntimeException e) {
                    // the offers, Nomad and Hondo do not wait for it; the measurement is tried again in a minute
                    Main.getInstance().getSLF4JLogger().error("Economy measurement failed, next try in a minute", e);
                }
            }
            boolean newOffers = !today.equals(offersDay);
            List<Material> offers = newOffers ? rotate() : List.of();
            List<Component> nomadNews = Nomad.isUpToDate(today) ? List.of() : Nomad.dailyUpdate(today);
            try {
                Hondo.dailyUpdate(today);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().error("Hondo's daily update failed, next try in a minute", e);
            }
            Tasks.sync(() -> {
                nomadNews.forEach(Bukkit::broadcast);
                if (!offers.isEmpty()) {
                    Component announcement = Text.section(Main.getChatPrefix() + "§6Der Händler hat neue Tagesangebote! §7(§a-"
                            + Math.round(dailyDiscount() * 100) + "%§7)");
                    Bukkit.broadcast(announcement);
                }
                ShopView.refreshAll();
                TabListUtil.updateTabForAll();
            });
            // the AI review last: its request can take minutes and holds nothing else back
            try {
                if (MarketReview.due(today)) {
                    if (snapshot == null) snapshot = Economy.snapshot(today);
                    if (snapshot != null && MarketReview.run(today, snapshot)) Tasks.sync(ShopView::refreshAll);
                }
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("AI market review could not start: {}", e.toString());
            }
        } finally {
            updating.set(false);
        }
    }

    /** Draws today's offers from the rotation pool and stores them (blocking). */
    public static List<Material> rotate() {
        LocalDate today = LocalDate.now(ZONE);
        int count = ConfigManager.getManager().getDailyOfferCount() + (Bankruptcy.isBroke() ? Bankruptcy.EXTRA_OFFERS : 0);
        List<Material> offers = drawOffers(count);
        MarketRepository.saveRotation(today, offers);
        dailyOffers = offers;
        offersDay = today;
        Main.getInstance().getSLF4JLogger().info("daily offers for {}: {}", today, offers);
        return offers;
    }

    // rules: at least 2 farm items to sell, 1 food, 1 rare item, the rest weighted by rotation_weight
    private static List<Material> drawOffers(int count) {
        List<MarketItem> pool = items.values().stream()
                .filter(item -> item.enabled() && !item.core() && item.rotationWeight() > 0 && (item.buyable() || item.sellable()))
                .filter(item -> EndAccess.isAvailable(item.material()))
                .toList();
        List<Material> picked = new ArrayList<>();
        Set<Material> used = new HashSet<>();
        pick(pool, used, picked, item -> item.category() == Category.FARMING && item.sellable(), Math.min(2, count));
        pick(pool, used, picked, item -> item.category() == Category.FOOD, Math.min(1, count - picked.size()));
        pick(pool, used, picked, item -> item.category() == Category.RARE, Math.min(1, count - picked.size()));
        pick(pool, used, picked, item -> true, count - picked.size());
        return List.copyOf(picked);
    }

    private static void pick(List<MarketItem> pool, Set<Material> used, List<Material> picked,
                             Predicate<MarketItem> filter, int amount) {
        for (int i = 0; i < amount; i++) {
            List<MarketItem> candidates = pool.stream().filter(item -> !used.contains(item.material())).filter(filter).toList();
            int totalWeight = candidates.stream().mapToInt(MarketItem::rotationWeight).sum();
            if (totalWeight <= 0) return;
            int roll = ThreadLocalRandom.current().nextInt(totalWeight);
            for (MarketItem candidate : candidates) {
                roll -= candidate.rotationWeight();
                if (roll < 0) {
                    used.add(candidate.material());
                    picked.add(candidate.material());
                    break;
                }
            }
        }
    }


    /* cache access (any thread) */

    public static @Nullable MarketItem get(@NotNull Material material) {
        return items.get(material);
    }

    /** The item as the start catalog ships it, null for items an admin added. */
    public static @Nullable MarketItem seed(@NotNull Material material) {
        return seeds.get(material);
    }

    public static Collection<MarketItem> all() {
        return items.values();
    }

    /** Stores the fresh row a trade returned. */
    public static void update(@Nullable MarketItem item) {
        if (item != null) items.put(item.material(), item);
    }

    public static void remove(@NotNull Material material) {
        items.remove(material);
    }

    /** The fixed catalog of a tab: enabled core items, cheapest first. */
    public static List<MarketItem> category(@NotNull Category category) {
        return items.values().stream()
                .filter(item -> item.enabled() && item.core() && item.category() == category && (item.buyable() || item.sellable()))
                .filter(item -> EndAccess.isAvailable(item.material()))
                .sorted(Comparator.comparingInt(MarketItem::basePrice).thenComparing(item -> item.material().name()))
                .toList();
    }

    public static List<MarketItem> dailyOffers() {
        List<MarketItem> offers = new ArrayList<>();
        for (Material material : dailyOffers) {
            MarketItem item = items.get(material);
            if (item != null && item.enabled()) offers.add(item);
        }
        return offers;
    }

    public static boolean isDailyOffer(@NotNull Material material) {
        return dailyOffers.contains(material);
    }

    public static double dailyDiscount() {
        double discount = ConfigManager.getManager().getDailyDiscount();
        return Bankruptcy.isBroke() ? Math.min(0.9, discount + Bankruptcy.EXTRA_DISCOUNT) : discount;
    }

    /** Net price of the random item, 0 if there is nothing to buy today. */
    public static int randomItemPrice() {
        List<MarketItem> buyable = dailyOffers().stream().filter(MarketItem::buyable).toList();
        if (buyable.isEmpty()) return 0;
        double average = buyable.stream().mapToInt(item -> item.buyTotal(1, dailyDiscount())).average().orElse(0);
        return (int) Math.max(1, Math.round(average * RANDOM_ITEM_SHARE));
    }

    public static @Nullable MarketItem randomDailyOffer() {
        // the price is an average: an item the shop pays more for than that is never the random item
        int price = randomItemPrice();
        List<MarketItem> buyable = dailyOffers().stream().filter(MarketItem::buyable)
                .filter(item -> !item.sellable() || Math.round(item.basePrice() * item.sellRatio()) < price).toList();
        return buyable.isEmpty() ? null : buyable.get(ThreadLocalRandom.current().nextInt(buyable.size()));
    }


    /* start catalog */

    /**
     * Inserts the start catalog entries the database does not know yet. Every entry has a catalog version ("since",
     * default 1); an existing database only gets the entries of newer versions, so adjusted or removed items stay as they are.
     */
    private static void seedCatalog() {
        try (InputStream stream = Main.class.getResourceAsStream("/market-catalog.json")) {
            if (stream == null) {
                Main.getInstance().getSLF4JLogger().warn("market-catalog.json is missing, the shop starts empty");
                return;
            }
            int known = MarketRepository.catalogVersion();
            if (known < 0) known = MarketRepository.count() > 0 ? 1 : 0;
            JsonArray entries = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
            int latest = known;
            int inserted = 0;
            Map<Material, MarketItem> shipped = new java.util.EnumMap<>(Material.class);
            for (JsonElement element : entries) {
                JsonObject json = element.getAsJsonObject();
                int since = json.has("since") ? json.get("since").getAsInt() : 1;
                latest = Math.max(latest, since);
                MarketItem item = parseSeed(json);
                if (item != null) shipped.put(item.material(), item);
                if (since <= known) continue;
                if (item != null && MarketRepository.insert(item)) inserted++;
            }
            seeds = Map.copyOf(shipped);
            MarketRepository.setCatalogVersion(latest);
            if (inserted > 0) Main.getInstance().getSLF4JLogger().info("added {} items to the market catalog (version {})", inserted, latest);
        } catch (Exception e) {
            Main.getInstance().getSLF4JLogger().error("Could not seed the market catalog", e);
        }
    }

    private static @Nullable MarketItem parseSeed(JsonObject json) {
        Material material = Material.matchMaterial(json.get("material").getAsString());
        Category category = Category.parse(json.get("category").getAsString());
        if (material == null || category == null) {
            Main.getInstance().getSLF4JLogger().warn("Skipping seed entry {}", json);
            return null;
        }
        return new MarketItem(material, category,
                optString(json, "name"),
                optString(json, "description"),
                json.has("amount") ? json.get("amount").getAsInt() : 1,
                json.get("price").getAsInt(),
                json.has("min") ? json.get("min").getAsInt() : null,
                json.has("max") ? json.get("max").getAsInt() : null,
                json.has("elasticity") ? json.get("elasticity").getAsDouble() : defaultElasticity(category),
                json.has("sellRatio") ? json.get("sellRatio").getAsDouble() : 0.5,
                !json.has("buyable") || json.get("buyable").getAsBoolean(),
                json.has("sellable") && json.get("sellable").getAsBoolean(),
                !json.has("core") || json.get("core").getAsBoolean(),
                json.has("weight") ? json.get("weight").getAsInt() : 1,
                true,
                0,
                json.has("taxClass") ? TaxClass.parse(json.get("taxClass").getAsString()) : null);
    }

    public static double defaultElasticity(Category category) {
        return switch (category) {
            case BLOCKS -> 0.0075;
            case RARE -> 0.09;
            case MISC -> 0.0225;
            default -> 0.015;
        };
    }

    private static @Nullable String optString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

}
