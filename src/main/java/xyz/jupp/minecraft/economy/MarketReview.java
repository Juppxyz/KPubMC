package xyz.jupp.minecraft.economy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.utils.EndAccess;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Daily AI review of the market (after the 0:00 update): the catalog, the trades of the last 7 days and the economy
 * snapshot go to OpenAI. The answer has three parts: a short market report for the players (/staatskasse), a note for
 * the admins (/shopadmin verlauf) and corrections of the catalog.
 * <p>
 * Corrections are applied only within hard limits that can make the shop pay out less, never more (see {@link #limited}),
 * within a band around the start catalog, at most twice per item a week, and never for protected goods (rare items,
 * ores, the jeweler's goods, luxury goods, netherite, items not from the start catalog). Everything else the AI can only
 * suggest in the admin note. Each change is logged in market_adjustments and can be undone with /shopadmin undo.
 * A failed review is tried again twice the same day (not when the API refuses for good, e.g. no credit left).
 */
public final class MarketReview {

    private MarketReview() {}

    public static final String API_KEY_ENV = "OPENAI_API_KEY";
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private static final int MAX_CHANGES = 10;
    private static final double MAX_BASE_STEP = 0.2;
    private static final double MAX_RATIO_STEP = 0.1;
    // the AI keeps base prices within this band around the start catalog; beyond it is an admin's decision
    private static final double SEED_LOW = 0.7;
    private static final double SEED_HIGH = 1.5;
    private static final double MIN_SELL_RATIO = 0.2;
    // at most this many AI changes per item within REPEAT_DAYS, so cuts do not pile up day after day
    private static final int MAX_CHANGES_PER_ITEM = 2;
    private static final int REPEAT_DAYS = 7;
    private static final int HISTORY_DAYS = 14;
    private static final int REPORT_MAX = 240;
    private static final int ADMIN_NOTE_MAX = 800;
    // min_price only sets the buy floor (selling has its own, MarketItem.SELL_FLOOR_SHARE): not the AI's to lower
    private static final Set<String> FIELDS = Set.of("base_price", "max_price", "sell_ratio");
    // a failed review is tried again after these minutes
    private static final long[] RETRY_MINUTES = {5, 20};
    private static final String REVIEWED_KEY = "ai_reviewed_day";
    private static final String ERROR_KEY = "ai_review_error";
    // the players' report talks about the market, not to the admins: with any of these it is not shown
    private static final Pattern ADVICE = Pattern.compile(
            "(?i)(sollte|empfehl|admin|katalog|elastiz|ankaufquote|vergüt|gelddruck|preisfehler|geldmenge|inflation|basispreis|/shopadmin)");

    private static final String INSTRUCTIONS = """
            Du begleitest einmal am Tag die Wirtschaft eines Minecraft-Servers (Währung: Schilling) und lieferst drei Dinge.

            1. 'report': der Marktbericht für die Spieler. Höchstens zwei kurze Sätze, zusammen höchstens 220 Zeichen, du-Form,
            lebendig wie ein Marktschreier, aber sachlich richtig. Nutze nur die Fakten aus 'facts': was heute im Angebot ist,
            was teurer oder günstiger geworden ist (mit Prozent), wofür der Händler gerade wenig zahlt, wie die Steuern stehen
            und ob sie steigen oder sinken (sind Stufe und Richtung gegensätzlich, sag beides, z. B. "Steuern noch niedrig,
            ziehen aber an"), ob der Staat pleite ist. Nenne Items mit ihrem deutschen Minecraft-Namen. Keine Ratschläge an
            Admins oder an den Server, nicht die Wörter sollte, empfehlen, Inflation, Geldmenge, Katalog, Basispreis,
            Ankaufquote, Preisfehler; keine Spielernamen, keine Befehle, keine Farbcodes, keine Emojis, nichts über morgen.

            2. 'adminNote': eine Notiz nur für die Admins, höchstens 600 Zeichen, sachlich und mit Zahlen: was dir am Shop
            auffällt (z. B. ein leicht farmbares Item, das massenhaft verkauft wird und am Mindestpreis liegt; ein Item, das
            trotz Angebot nie gekauft wird; Preise, die nicht zueinander passen), was du geändert hast und was ein Admin
            entscheiden sollte, gern als Befehl "/shopadmin set <MATERIAL> <feld> <wert>". Geschützte Items (protected=true)
            darfst du nicht ändern, nur hier ansprechen.

            3. 'changes': Korrekturen am Shop-Katalog, nur wenn klar begründet, lieber keine, höchstens 10. Preise gelten pro
            Paket ('amount' Stück). Preismodell: Kaufpreis = basePrice * e^(elasticity * demand), begrenzt auf
            [minPrice, maxPrice] (null: 25 % bzw. 400 % des Basispreises). Verkaufspreis = dieselbe Kurve, aber bis hinunter auf
            5 % des Basispreises (minPrice gilt nur für den Kauf), höchstens basePrice, mal sellRatio.
            'demand' steigt mit jedem gekauften Paket, sinkt mit jedem verkauften und zerfällt mit der Zeit.
            Deine Änderungen dürfen den Shop nur weniger auszahlen lassen: sell_ratio nur senken (bis 0,2, höchstens 0,1 am
            Tag), base_price höchstens ±20 % am Tag und zwischen 70 % und
            150 % von seedBasePrice (bei verkaufbaren Items nur senken),
            max_price nur anheben (mindestens das Doppelte des Basispreises). Was darüber hinausgeht, gehört in die adminNote.
            Ein Item, das nie gekauft wurde, ist kein Befund, wenn es kaum im Angebot war (offerDays7 < 2) oder endLocked ist.
            Schau dir 'recentAdjustments' an: ändere kein Item Tag für Tag weiter in dieselbe Richtung und mach Änderungen
            nicht direkt wieder rückgängig. 'reason' kurz auf Deutsch (höchstens 150 Zeichen).""";

    private static volatile @Nullable LocalDate reviewedDay;
    private static volatile boolean reviewedLoaded;
    private static volatile @Nullable LocalDate attemptDay;
    private static volatile int attempts;
    private static volatile Instant nextAttempt = Instant.EPOCH;


    /* when it runs */

    private static @Nullable String apiKey() {
        // the key from config.json ("openAiApiKey"), otherwise from the environment variable
        String apiKey = ConfigManager.getManager().getOpenAiApiKey();
        if (apiKey.isBlank()) apiKey = System.getenv(API_KEY_ENV);
        return apiKey == null || apiKey.isBlank() ? null : apiKey;
    }

    /** Worker (blocking the first time): the review of the day is still to do and an attempt is due now. */
    static boolean due(@NotNull LocalDate day) {
        if (!ConfigManager.getManager().isAiReview() || apiKey() == null) return false;
        if (!reviewedLoaded) {
            String stored = meta(REVIEWED_KEY);
            reviewedDay = stored == null ? null : LocalDate.parse(stored);
            reviewedLoaded = true;
        }
        if (day.equals(reviewedDay)) return false;
        if (!day.equals(attemptDay)) {
            attemptDay = day;
            attempts = 0;
            nextAttempt = Instant.EPOCH;
        }
        return attempts <= RETRY_MINUTES.length && !Instant.now().isBefore(nextAttempt);
    }

    /**
     * Blocking: one attempt of the review for the day; true if it changed the catalog. Never throws for API problems,
     * a failure waits for the next try.
     */
    static boolean run(@NotNull LocalDate day, @NotNull Economy.Snapshot snapshot) {
        String apiKey = apiKey();
        if (apiKey == null) return false;
        attempts++;
        try {
            String content = request(apiKey, ConfigManager.getManager().getOpenAiModel(), input(day, snapshot).toString());
            JsonObject result = JsonParser.parseString(content).getAsJsonObject();
            // an attempt that failed after its changes were written must not cut the same goods a second time today
            Boolean changedToday = Database.queryOne("SELECT EXISTS (SELECT 1 FROM market_adjustments WHERE source = 'KI' AND day = ?)",
                    row -> row.getBoolean(1), Date.valueOf(day));
            List<String> applied = Boolean.TRUE.equals(changedToday) ? List.of() : apply(result, day, "KI");
            String report = playerReport(string(result, "report"));
            String adminNote = clip(clean(string(result, "adminNote")), ADMIN_NOTE_MAX);
            Economy.saveAiReview(day, report, adminNote.isEmpty() ? null : adminNote);
            setMeta(REVIEWED_KEY, day.toString());
            reviewedDay = day;
            Database.update("DELETE FROM market_meta WHERE key = ?", ERROR_KEY);
            Main.getInstance().getSLF4JLogger().info("AI market review: {} ({} changes applied: {}; admin note: {})",
                    report == null ? "(no player report)" : report, applied.size(), applied, adminNote);
            return !applied.isEmpty();
        } catch (Exception e) {
            boolean retry = attempts <= RETRY_MINUTES.length && retryable(e);
            if (retry) {
                nextAttempt = Instant.now().plus(Duration.ofMinutes(RETRY_MINUTES[attempts - 1]));
            } else {
                attempts = RETRY_MINUTES.length + 1;
            }
            String error = e.toString();
            try {
                setMeta(ERROR_KEY, day + ": " + error.substring(0, Math.min(300, error.length())));
            } catch (RuntimeException ignored) {
                // the database is the problem itself
            }
            Main.getInstance().getSLF4JLogger().warn("AI market review failed ({}), the update continues without it: {}",
                    retry ? "next try in " + RETRY_MINUTES[attempts - 1] + " min" : "no more tries today", error);
            return false;
        }
    }

    // no credit left, a wrong key or a broken request do not get better by asking again
    private static boolean retryable(Exception e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("insufficient_quota")) return false;
        return !(message.contains("OpenAI HTTP 400") || message.contains("OpenAI HTTP 401") || message.contains("OpenAI HTTP 403")
                || message.contains("OpenAI HTTP 404"));
    }

    /** The last failure of the review ("day: error"), null after a success. Blocking. */
    public static @Nullable String lastError() {
        return meta(ERROR_KEY);
    }


    /* request */

    private static JsonObject input(LocalDate day, Economy.Snapshot snapshot) {
        JsonObject input = new JsonObject();
        input.addProperty("day", day.toString());

        JsonObject economy = new JsonObject();
        economy.addProperty("moneySupply", snapshot.moneySupply());
        economy.addProperty("activePlayers", snapshot.activePlayers());
        economy.addProperty("moneyPerActivePlayer", snapshot.perPlayer());
        economy.addProperty("treasury", snapshot.treasury());
        economy.addProperty("tradeVolume24h", snapshot.tradeVolume());
        economy.addProperty("taxFactor", snapshot.factor());
        economy.addProperty("taxFactorBounds", ConfigManager.getManager().getEconomyFactorMin() + " - " + ConfigManager.getManager().getEconomyFactorMax());
        economy.addProperty("note", snapshot.note());
        input.add("economy", economy);

        JsonObject taxes = new JsonObject();
        for (TaxClass taxClass : TaxClass.values()) taxes.addProperty(taxClass.name(), Taxes.rate(taxClass));
        input.add("taxRates", taxes);
        input.add("facts", facts());

        Map<String, long[]> volumes = weeklyVolumes();
        Map<String, Integer> offerDays = offerDays(day);
        JsonArray items = new JsonArray();
        for (MarketItem item : Market.all()) {
            if (!item.enabled()) continue;
            MarketItem seed = Market.seed(item.material());
            JsonObject json = new JsonObject();
            json.addProperty("material", item.material().name());
            json.addProperty("category", item.category().name());
            json.addProperty("taxClass", item.taxClass().name());
            json.addProperty("protected", isProtected(item));
            json.addProperty("amount", item.amount());
            json.addProperty("basePrice", item.basePrice());
            json.addProperty("seedBasePrice", seed == null ? null : seed.basePrice());
            json.addProperty("minPrice", item.minPrice());
            json.addProperty("maxPrice", item.maxPrice());
            json.addProperty("buyPrice", item.buyPrice());
            json.addProperty("sellPrice", item.sellPrice());
            json.addProperty("sellRatio", item.sellRatio());
            json.addProperty("seedSellRatio", seed == null ? null : seed.sellRatio());
            json.addProperty("elasticity", item.elasticity());
            json.addProperty("demand", Math.round(item.demand() * 100) / 100.0);
            json.addProperty("buyable", item.buyable());
            json.addProperty("sellable", item.sellable());
            json.addProperty("dailyOfferPool", !item.core());
            json.addProperty("offerDays7", offerDays.getOrDefault(item.material().name(), 0));
            json.addProperty("endLocked", !EndAccess.isAvailable(item.material()));
            long[] volume = volumes.getOrDefault(item.material().name(), new long[4]);
            json.addProperty("bought7d", volume[0]);
            json.addProperty("boughtNet7d", volume[1]);
            json.addProperty("sold7d", volume[2]);
            json.addProperty("soldNet7d", volume[3]);
            items.add(json);
        }
        input.add("items", items);
        input.add("recentAdjustments", history(day));
        return input;
    }

    // what the players' report may talk about, computed here so the AI does not have to guess
    private static JsonObject facts() {
        JsonObject facts = new JsonObject();
        JsonArray offers = new JsonArray();
        for (MarketItem offer : Market.dailyOffers()) offers.add(offer.material().name());
        facts.add("offersToday", offers);
        facts.addProperty("offerDiscountPercent", Math.round(Market.dailyDiscount() * 100));

        // goods a player can buy today whose price moved at least 10 % away from normal
        List<MarketItem> buyable = Market.all().stream()
                .filter(item -> item.enabled() && item.buyable() && EndAccess.isAvailable(item.material()))
                .filter(item -> item.core() || Market.isDailyOffer(item.material()))
                .toList();
        facts.add("pricesUp", movers(buyable.stream().filter(item -> item.trend() >= 0.1)
                .sorted(Comparator.comparingDouble(MarketItem::trend).reversed()).limit(3).toList()));
        facts.add("pricesDown", movers(buyable.stream().filter(item -> item.trend() <= -0.1)
                .sorted(Comparator.comparingDouble(MarketItem::trend)).limit(3).toList()));

        // goods the shop pays clearly less for than normal (many were sold)
        JsonArray sellingLow = new JsonArray();
        Market.all().stream()
                .filter(item -> item.enabled() && item.sellable() && EndAccess.isAvailable(item.material()))
                .filter(item -> item.core() || Market.isDailyOffer(item.material()))
                .filter(item -> normalSell(item) > 0 && item.sellPrice() < normalSell(item) * 0.6)
                .sorted(Comparator.comparingDouble(item -> (double) item.sellPrice() / normalSell(item)))
                .limit(3)
                .forEach(item -> {
                    JsonObject json = new JsonObject();
                    json.addProperty("material", item.material().name());
                    json.addProperty("percentOfNormal", Math.round(100.0 * item.sellPrice() / normalSell(item)));
                    sellingLow.add(json);
                });
        facts.add("sellingLow", sellingLow);

        facts.addProperty("taxLevel", Economy.levelPlain());
        facts.addProperty("taxDirection", Economy.directionWord());
        facts.addProperty("stateBroke", Bankruptcy.isBroke());
        return facts;
    }

    private static long normalSell(MarketItem item) {
        return Math.round(item.basePrice() * item.sellRatio());
    }

    private static JsonArray movers(List<MarketItem> items) {
        JsonArray array = new JsonArray();
        for (MarketItem item : items) {
            JsonObject json = new JsonObject();
            json.addProperty("material", item.material().name());
            json.addProperty("percent", Math.round(item.trend() * 100));
            array.add(json);
        }
        return array;
    }

    // material -> {bought quantity, bought net, sold quantity, sold net} of the last 7 days; Hondo's exchanges move no
    // money and are left out
    private static Map<String, long[]> weeklyVolumes() {
        Map<String, long[]> volumes = new HashMap<>();
        Database.query("SELECT material, kind, SUM(quantity), SUM(net) FROM market_transactions "
                + "WHERE created_at >= now() - interval '7 days' AND moves_money GROUP BY material, kind", row -> {
            long[] volume = volumes.computeIfAbsent(row.getString(1), key -> new long[4]);
            int offset = "BUY".equals(row.getString(2)) ? 0 : 2;
            volume[offset] = row.getLong(3);
            volume[offset + 1] = row.getLong(4);
            return null;
        });
        return volumes;
    }

    // material -> days it was a daily offer in the last 7 days
    private static Map<String, Integer> offerDays(LocalDate day) {
        Map<String, Integer> days = new HashMap<>();
        Database.query("SELECT material, COUNT(DISTINCT day) FROM market_rotation WHERE day > ? AND day <= ? GROUP BY material", row -> {
            days.put(row.getString(1), row.getInt(2));
            return null;
        }, Date.valueOf(day.minusDays(7)), Date.valueOf(day));
        return days;
    }

    // the changes of the last two weeks per item and field: from where to where, how often, by whom
    private static JsonArray history(LocalDate day) {
        Map<String, JsonObject> byField = new LinkedHashMap<>();
        Database.query("SELECT day, material, field, old_value, new_value, source FROM market_adjustments WHERE day >= ? ORDER BY id", row -> {
            String key = row.getString(2) + ":" + row.getString(3);
            JsonObject json = byField.get(key);
            if (json == null) {
                json = new JsonObject();
                json.addProperty("material", row.getString(2));
                json.addProperty("field", row.getString(3));
                json.addProperty("from", row.getString(4));
                json.addProperty("changes", 0);
                json.add("by", new JsonArray());
                byField.put(key, json);
            }
            json.addProperty("to", row.getString(5));
            json.addProperty("lastDay", row.getDate(1).toLocalDate().toString());
            json.addProperty("changes", json.get("changes").getAsInt() + 1);
            JsonArray by = json.getAsJsonArray("by");
            String source = row.getString(6);
            boolean known = false;
            for (JsonElement element : by) known |= element.getAsString().equals(source);
            if (!known) by.add(source);
            return null;
        }, Date.valueOf(day.minusDays(HISTORY_DAYS)));
        JsonArray history = new JsonArray();
        byField.values().forEach(history::add);
        return history;
    }

    private static String request(String apiKey, String model, String input) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(message("system", INSTRUCTIONS));
        messages.add(message("user", input));
        body.add("messages", messages);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        JsonObject schema = new JsonObject();
        schema.addProperty("name", "market_review");
        schema.addProperty("strict", true);
        schema.add("schema", JsonParser.parseString(RESPONSE_SCHEMA));
        format.add("json_schema", schema);
        body.add("response_format", format);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String error = response.body();
            throw new IllegalStateException("OpenAI HTTP " + response.statusCode() + ": " + error.substring(0, Math.min(300, error.length())));
        }
        JsonObject message = JsonParser.parseString(response.body()).getAsJsonObject()
                .getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        if (message.has("refusal") && !message.get("refusal").isJsonNull()) {
            throw new IllegalStateException("OpenAI refused: " + message.get("refusal").getAsString());
        }
        if (!message.has("content") || message.get("content").isJsonNull()) throw new IllegalStateException("OpenAI sent no content");
        return message.get("content").getAsString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static final String RESPONSE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "report": {"type": "string"},
                "adminNote": {"type": "string"},
                "changes": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "material": {"type": "string"},
                      "field": {"type": "string", "enum": ["base_price", "max_price", "sell_ratio"]},
                      "value": {"type": "number"},
                      "reason": {"type": "string"}
                    },
                    "required": ["material", "field", "value", "reason"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["report", "adminNote", "changes"],
              "additionalProperties": false
            }""";


    /* the texts */

    private static String string(JsonObject result, String key) {
        JsonElement element = result.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    /** The players' report: plain, short, and about the market only; null (no report today) if it talks to the admins. */
    static @Nullable String playerReport(@NotNull String text) {
        String report = clip(clean(text), REPORT_MAX);
        if (report.isEmpty() || ADVICE.matcher(report).find()) return null;
        return report;
    }

    // no colour codes, control characters or line breaks: the text goes into a § line of the chat
    static String clean(@NotNull String text) {
        return text.replaceAll("[§&][0-9a-fk-orxA-FK-ORX]", "").replace("§", "").replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
    }

    // at most max characters, cut at the end of a sentence if one is near, otherwise at a word
    static String clip(@NotNull String text, int max) {
        if (text.length() <= max) return text;
        String head = text.substring(0, max);
        int sentence = Math.max(head.lastIndexOf(". "), Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        if (sentence >= max / 2) return head.substring(0, sentence + 1);
        int word = head.lastIndexOf(' ');
        return (word > 0 ? head.substring(0, word) : head) + "…";
    }


    /* guard rails */

    /**
     * Goods the AI never changes: rare items and ores (the diamond and netherite guard), the jeweler's goods, luxury
     * goods, netherite and anything not from the start catalog (no seed values to stay near). An admin still can.
     */
    static boolean isProtected(@NotNull MarketItem item) {
        return item.category() == Category.RARE || item.category() == Category.ORES || item.taxClass() == TaxClass.LUXURY
                || Hondo.GOODS.contains(item.material()) || item.material().name().startsWith("NETHERITE")
                || Market.seed(item.material()) == null;
    }

    /** Applies the changes of an AI answer within the limits; returns a short description per applied change. */
    static List<String> apply(JsonObject result, LocalDate day, String source) {
        List<String> applied = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> recent = recentAiChanges(day);
        JsonArray changes = result.has("changes") ? result.getAsJsonArray("changes") : new JsonArray();
        try {
            for (JsonElement element : changes) {
                if (applied.size() >= MAX_CHANGES) break;
                JsonObject change = element.getAsJsonObject();
                Material material = Material.matchMaterial(change.get("material").getAsString());
                String field = change.get("field").getAsString();
                MarketItem item = material == null ? null : Market.get(material);
                if (item == null || !item.enabled() || !FIELDS.contains(field) || isProtected(item)
                        || !EndAccess.isAvailable(material) || !seen.add(material + ":" + field)) continue;
                if (recent.getOrDefault(material.name(), 0) >= MAX_CHANGES_PER_ITEM) continue;
                MarketItem seed = Market.seed(material);
                if (seed == null) continue;

                Object newValue = limited(item, seed, field, change.get("value").getAsDouble());
                if (newValue == null || newValue.equals(effective(item, field))) continue;
                Object oldValue = stored(item, field);

                String reason = change.get("reason").getAsString().trim();
                if (reason.length() > 200) reason = reason.substring(0, 200);
                MarketRepository.update(material, field, newValue);
                MarketRepository.logAdjustment(day, material, field, oldValue, newValue, reason, source);
                recent.merge(material.name(), 1, Integer::sum);
                applied.add(material + " " + field + " " + text(oldValue) + " -> " + text(newValue));
            }
        } finally {
            // also after a failure in the middle, so the cache shows what was written
            if (!applied.isEmpty()) Market.reload();
        }
        return applied;
    }

    /**
     * The value the AI may set for the field, or null if no such change is allowed. The shop may only pay out less:
     * sell ratio only down, base price of a sellable good only down, the price floor only lower, the ceiling only higher
     * (not for a sellable good whose ceiling an admin put below its base price: that would raise the payout); the base
     * price stays within SEED_LOW..SEED_HIGH of the start catalog. Raising anything back is an admin's decision.
     */
    static @Nullable Object limited(@NotNull MarketItem item, @NotNull MarketItem seed, @NotNull String field, double value) {
        switch (field) {
            case "base_price" -> {
                int old = item.basePrice();
                int lower = Math.max(1, Math.max((int) Math.ceil(old * (1 - MAX_BASE_STEP)), (int) Math.ceil(seed.basePrice() * SEED_LOW)));
                // a higher base price raises what the shop pays for a sellable good
                int upper = item.sellable() ? old : Math.min((int) Math.floor(old * (1 + MAX_BASE_STEP)), (int) Math.floor(seed.basePrice() * SEED_HIGH));
                if (item.minPrice() != null) lower = Math.max(lower, item.minPrice());
                if (item.maxPrice() != null) upper = Math.min(upper, item.maxPrice());
                if (upper < lower) return null;
                return (int) Math.max(lower, Math.min(upper, Math.round(value)));
            }
            case "sell_ratio" -> {
                double old = item.sellRatio();
                double lower = Math.max(Math.min(MIN_SELL_RATIO, old), old - MAX_RATIO_STEP);
                return round(Math.max(lower, Math.min(old, value)));
            }
            case "min_price" -> {
                // a higher floor would keep dumped goods paying full price
                int lower = Math.max(1, (int) Math.ceil(item.basePrice() * 0.1));
                int upper = Math.min(item.effectiveMinPrice(), (int) Math.ceil(item.basePrice() * 0.25));
                if (upper < lower) return null;
                return (int) Math.max(lower, Math.min(upper, Math.round(value)));
            }
            case "max_price" -> {
                // a lower ceiling would stop the price reacting to buyers; a ceiling below the base price caps the payout
                if (item.sellable() && item.effectiveMaxPrice() < item.basePrice()) return null;
                int lower = Math.max(item.effectiveMaxPrice(), item.basePrice() * 2);
                long upper = item.basePrice() * 10L;
                if (upper < lower) return null;
                return (int) Math.max(lower, Math.min(upper, Math.round(value)));
            }
            default -> {
                return null;
            }
        }
    }

    // the value in force (an automatic floor or ceiling counts as its number), to skip changes that change nothing
    private static Object effective(MarketItem item, String field) {
        return switch (field) {
            case "base_price" -> item.basePrice();
            case "min_price" -> item.effectiveMinPrice();
            case "max_price" -> item.effectiveMaxPrice();
            default -> item.sellRatio();
        };
    }

    // the value as stored (null for an automatic floor or ceiling), for the log and /shopadmin undo
    private static @Nullable Object stored(MarketItem item, String field) {
        return switch (field) {
            case "base_price" -> item.basePrice();
            case "min_price" -> item.minPrice();
            case "max_price" -> item.maxPrice();
            default -> item.sellRatio();
        };
    }

    // material -> the AI's changes within REPEAT_DAYS
    private static Map<String, Integer> recentAiChanges(LocalDate day) {
        Map<String, Integer> recent = new HashMap<>();
        Database.query("SELECT material, COUNT(*) FROM market_adjustments WHERE source = 'KI' AND day > ? GROUP BY material", row -> {
            recent.put(row.getString(1), row.getInt(2));
            return null;
        }, Date.valueOf(day.minusDays(REPEAT_DAYS)));
        return recent;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    static String text(Object value) {
        return value == null ? "-" : value instanceof Double number ? String.format(Locale.ROOT, "%.3f", number) : value.toString();
    }

    /** The value as stored in the adjustment log: exact, so /shopadmin undo restores it unchanged. */
    static String exact(Object value) {
        return value == null ? "-" : value instanceof Double number ? java.math.BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
                : value.toString();
    }


    /* meta */

    private static @Nullable String meta(String key) {
        return Database.queryOne("SELECT value FROM market_meta WHERE key = ?", row -> row.getString(1), key);
    }

    private static void setMeta(String key, String value) {
        Database.update("INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", key, value);
    }

}
