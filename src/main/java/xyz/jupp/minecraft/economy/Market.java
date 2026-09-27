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
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
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
import java.util.function.Predicate;

/**
 * The market: catalog cache for the GUI (main thread reads, no database), daily offers and the demand decay.
 * The database stays the source of truth; every trade returns the fresh catalog row and updates the cache.
 */
public final class Market {

    private Market() {}

    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private static final long DECAY_INTERVAL_MINUTES = 10;
    // the random item costs this share of the average daily offer
    private static final double RANDOM_ITEM_SHARE = 0.8;

    private static final Map<Material, MarketItem> items = new ConcurrentHashMap<>();
    private static volatile List<Material> dailyOffers = List.of();
    private static volatile LocalDate offersDay = null;


    /* lifecycle (blocking parts run in onEnable or on workers) */

    public static void load() {
        seedIfEmpty();
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
    }

    public static void startTasks() {
        long period = 20L * 60 * DECAY_INTERVAL_MINUTES;
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), Market::decay, period, period);
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), Market::rotateOnNewDay, 20L * 60, 20L * 60);
    }

    private static void decay() {
        double halfLifeHours = ConfigManager.getManager().getDemandHalfLifeHours();
        double factor = Math.pow(0.5, DECAY_INTERVAL_MINUTES / (60.0 * halfLifeHours));
        MarketRepository.decayDemand(factor);
        reload();
        Tasks.sync(ShopView::refreshAll);
    }

    private static void rotateOnNewDay() {
        if (LocalDate.now(ZONE).equals(offersDay)) return;
        List<Material> offers = rotate();
        Tasks.sync(() -> {
            if (!offers.isEmpty()) {
                Component announcement = Text.section(Main.getChatPrefix() + "§6Der Händler hat neue Tagesangebote! §8(§a-"
                        + Math.round(dailyDiscount() * 100) + "%§8)");
                Bukkit.broadcast(announcement);
            }
            ShopView.refreshAll();
        });
    }

    /** Draws today's offers from the rotation pool and stores them (blocking). */
    public static List<Material> rotate() {
        LocalDate today = LocalDate.now(ZONE);
        List<Material> offers = drawOffers(ConfigManager.getManager().getDailyOfferCount());
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
        return ConfigManager.getManager().getDailyDiscount();
    }

    /** Net price of the random item, 0 if there is nothing to buy today. */
    public static int randomItemPrice() {
        List<MarketItem> buyable = dailyOffers().stream().filter(MarketItem::buyable).toList();
        if (buyable.isEmpty()) return 0;
        double average = buyable.stream().mapToInt(item -> item.buyTotal(1, dailyDiscount())).average().orElse(0);
        return (int) Math.max(1, Math.round(average * RANDOM_ITEM_SHARE));
    }

    public static @Nullable MarketItem randomDailyOffer() {
        List<MarketItem> buyable = dailyOffers().stream().filter(MarketItem::buyable).toList();
        return buyable.isEmpty() ? null : buyable.get(ThreadLocalRandom.current().nextInt(buyable.size()));
    }


    /* start catalog */

    private static void seedIfEmpty() {
        if (MarketRepository.count() > 0) return;
        try (InputStream stream = Main.class.getResourceAsStream("/market-catalog.json")) {
            if (stream == null) {
                Main.getInstance().getSLF4JLogger().warn("market-catalog.json is missing, the shop starts empty");
                return;
            }
            JsonArray entries = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
            int inserted = 0;
            for (JsonElement element : entries) {
                MarketItem item = parseSeed(element.getAsJsonObject());
                if (item != null && MarketRepository.insert(item)) inserted++;
            }
            Main.getInstance().getSLF4JLogger().info("seeded the market catalog with {} items", inserted);
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
                0);
    }

    public static double defaultElasticity(Category category) {
        return switch (category) {
            case BLOCKS -> 0.01;
            case RARE -> 0.12;
            case MISC -> 0.03;
            default -> 0.02;
        };
    }

    private static @Nullable String optString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

}
