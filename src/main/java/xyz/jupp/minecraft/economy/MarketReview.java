package xyz.jupp.minecraft.economy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Daily AI review of the market (part of the 0:00 update): the catalog, the trades of the last 7 days and the economy
 * snapshot go to OpenAI, which answers with corrections. Only corrections within hard limits are applied
 * (price fields only, base price at most ±20 % per day, at most 10 changes); each one is logged in market_adjustments
 * and can be undone with /shopadmin undo.
 */
public final class MarketReview {

    private MarketReview() {}

    public static final String API_KEY_ENV = "OPENAI_API_KEY";
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private static final int MAX_CHANGES = 10;
    private static final double MAX_BASE_STEP = 0.2;
    private static final double MAX_RATIO_STEP = 0.1;
    private static final double MAX_ELASTICITY_STEP = 0.05;
    private static final Set<String> FIELDS = Set.of("base_price", "min_price", "max_price", "sell_ratio", "elasticity");

    private static final String INSTRUCTIONS = """
            Du prüfst einmal täglich die Wirtschaft eines Minecraft-Servers (Währung: Schilling) und korrigierst Fehler und
            Missstände im Shop-Katalog. Preise gelten pro Paket ('amount' Stück).
            Preismodell: Kaufpreis = basePrice * e^(elasticity * demand), begrenzt auf [minPrice, maxPrice]
            (null bedeutet automatisch 25 % bzw. 400 % des Basispreises). Verkaufspreis = min(Kaufpreis, basePrice) * sellRatio.
            'demand' steigt mit jedem gekauften und sinkt mit jedem verkauften Paket und zerfällt mit der Zeit.
            Käufe werden je nach Steuerklasse besteuert; die Steuern fließen in die Staatskasse.
            Typische Missstände: ein leicht farmbares Item wird massenhaft verkauft und liegt dauerhaft am Mindestpreis
            (Basispreis oder Ankaufquote zu hoch, Gelddruckmaschine); ein Item wird nie gekauft, weil es zu teuer ist;
            Preise passen nicht zueinander (z. B. ein Block billiger als seine Bestandteile); Arbitrage zwischen Kauf- und
            Verkaufspreisen. Items mit 'Kaufen beim Juwelier' verkauft der Juwelier zum Kaufpreis pro Stück.
            Regeln: Ändere nur, was klar begründet ist, lieber keine Änderung als eine unnötige. Höchstens 10 Änderungen.
            Erlaubte Felder: base_price (ganze Zahl, höchstens ±20 %), min_price, max_price (ganze Zahlen),
            sell_ratio (0 bis 0.9), elasticity (0 bis 0.3). Mache die Anpassungen der letzten Tage ('recentAdjustments')
            nicht direkt wieder rückgängig. 'reason' kurz auf Deutsch (höchstens 150 Zeichen).
            'summary': ein bis zwei Sätze auf Deutsch zum Zustand der Wirtschaft für die Spieler.""";

    /** Blocking: runs the review for the day, if enabled and an API key is set. Never throws for API problems. */
    public static void run(@NotNull LocalDate day, @NotNull Economy.Snapshot snapshot) {
        if (!ConfigManager.getManager().isAiReview()) return;
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            Main.getInstance().getSLF4JLogger().info("AI market review skipped: environment variable {} is not set", API_KEY_ENV);
            return;
        }
        try {
            String content = request(apiKey, ConfigManager.getManager().getOpenAiModel(), input(day, snapshot).toString());
            JsonObject result = JsonParser.parseString(content).getAsJsonObject();
            List<String> applied = apply(result, day, "KI");
            String summary = result.get("summary").getAsString().trim();
            Economy.saveAiSummary(day, summary);
            Main.getInstance().getSLF4JLogger().info("AI market review: {} ({} changes applied: {})", summary, applied.size(), applied);
        } catch (Exception e) {
            Main.getInstance().getSLF4JLogger().warn("AI market review failed, the update continues without it: {}", e.toString());
        }
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
        economy.addProperty("note", snapshot.note());
        input.add("economy", economy);

        JsonObject taxes = new JsonObject();
        for (TaxClass taxClass : TaxClass.values()) taxes.addProperty(taxClass.name(), Taxes.rate(taxClass));
        input.add("taxRates", taxes);

        Map<String, long[]> volumes = weeklyVolumes();
        JsonArray items = new JsonArray();
        for (MarketItem item : Market.all()) {
            if (!item.enabled()) continue;
            JsonObject json = new JsonObject();
            json.addProperty("material", item.material().name());
            json.addProperty("category", item.category().name());
            json.addProperty("taxClass", item.taxClass().name());
            json.addProperty("amount", item.amount());
            json.addProperty("basePrice", item.basePrice());
            json.addProperty("minPrice", item.minPrice());
            json.addProperty("maxPrice", item.maxPrice());
            json.addProperty("buyPrice", item.buyPrice());
            json.addProperty("sellPrice", item.sellPrice());
            json.addProperty("sellRatio", item.sellRatio());
            json.addProperty("elasticity", item.elasticity());
            json.addProperty("demand", Math.round(item.demand() * 100) / 100.0);
            json.addProperty("buyable", item.buyable());
            json.addProperty("sellable", item.sellable());
            json.addProperty("dailyOfferPool", !item.core());
            long[] volume = volumes.getOrDefault(item.material().name(), new long[4]);
            json.addProperty("bought7d", volume[0]);
            json.addProperty("boughtNet7d", volume[1]);
            json.addProperty("sold7d", volume[2]);
            json.addProperty("soldNet7d", volume[3]);
            items.add(json);
        }
        input.add("items", items);

        JsonArray recent = new JsonArray();
        for (MarketRepository.Adjustment adjustment : MarketRepository.recentAdjustments(20)) {
            JsonObject json = new JsonObject();
            json.addProperty("day", adjustment.day().toString());
            json.addProperty("material", adjustment.material());
            json.addProperty("field", adjustment.field());
            json.addProperty("from", adjustment.oldValue());
            json.addProperty("to", adjustment.newValue());
            json.addProperty("by", adjustment.source());
            recent.add(json);
        }
        input.add("recentAdjustments", recent);
        return input;
    }

    // material -> {bought quantity, bought net, sold quantity, sold net} of the last 7 days
    private static Map<String, long[]> weeklyVolumes() {
        Map<String, long[]> volumes = new HashMap<>();
        Database.query("SELECT material, kind, SUM(quantity), SUM(net) FROM market_transactions "
                + "WHERE created_at >= now() - interval '7 days' GROUP BY material, kind", row -> {
            long[] volume = volumes.computeIfAbsent(row.getString(1), key -> new long[4]);
            int offset = "BUY".equals(row.getString(2)) ? 0 : 2;
            volume[offset] = row.getLong(3);
            volume[offset + 1] = row.getLong(4);
            return null;
        });
        return volumes;
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
                "summary": {"type": "string"},
                "changes": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "material": {"type": "string"},
                      "field": {"type": "string", "enum": ["base_price", "min_price", "max_price", "sell_ratio", "elasticity"]},
                      "value": {"type": "number"},
                      "reason": {"type": "string"}
                    },
                    "required": ["material", "field", "value", "reason"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["summary", "changes"],
              "additionalProperties": false
            }""";


    /* guard rails (also used by the tests) */

    /** Applies the changes of an AI answer within the limits; returns a short description per applied change. */
    static List<String> apply(JsonObject result, LocalDate day, String source) {
        List<String> applied = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray changes = result.has("changes") ? result.getAsJsonArray("changes") : new JsonArray();
        for (JsonElement element : changes) {
            if (applied.size() >= MAX_CHANGES) break;
            JsonObject change = element.getAsJsonObject();
            Material material = Material.matchMaterial(change.get("material").getAsString());
            String field = change.get("field").getAsString();
            MarketItem item = material == null ? null : Market.get(material);
            if (item == null || !FIELDS.contains(field) || !seen.add(material + ":" + field)) continue;

            double value = change.get("value").getAsDouble();
            Object oldValue;
            Object newValue;
            switch (field) {
                case "base_price" -> {
                    int old = item.basePrice();
                    int lower = Math.max(1, (int) Math.ceil(old * (1 - MAX_BASE_STEP)));
                    int upper = (int) Math.floor(old * (1 + MAX_BASE_STEP));
                    if (item.minPrice() != null) lower = Math.max(lower, item.minPrice());
                    if (item.maxPrice() != null) upper = Math.min(upper, item.maxPrice());
                    oldValue = old;
                    newValue = (int) Math.max(lower, Math.min(upper, Math.round(value)));
                }
                case "min_price" -> {
                    oldValue = item.minPrice();
                    newValue = (int) Math.max(Math.max(1, Math.ceil(item.basePrice() * 0.1)), Math.min(item.basePrice(), Math.round(value)));
                }
                case "max_price" -> {
                    oldValue = item.maxPrice();
                    newValue = (int) Math.max(item.basePrice(), Math.min(item.basePrice() * 10L, Math.round(value)));
                }
                case "sell_ratio" -> {
                    double old = item.sellRatio();
                    oldValue = old;
                    newValue = round(Math.max(Math.max(0, old - MAX_RATIO_STEP), Math.min(Math.min(0.9, old + MAX_RATIO_STEP), value)));
                }
                default -> {
                    double old = item.elasticity();
                    oldValue = old;
                    newValue = round(Math.max(Math.max(0, old - MAX_ELASTICITY_STEP), Math.min(Math.min(0.3, old + MAX_ELASTICITY_STEP), value)));
                }
            }
            if (newValue.equals(oldValue)) continue;

            String reason = change.get("reason").getAsString().trim();
            if (reason.length() > 200) reason = reason.substring(0, 200);
            MarketRepository.update(material, field, newValue);
            MarketRepository.logAdjustment(day, material, field, oldValue, newValue, reason, source);
            applied.add(material + " " + field + " " + text(oldValue) + " -> " + text(newValue));
        }
        if (!applied.isEmpty()) Market.reload();
        return applied;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    static String text(Object value) {
        return value == null ? "-" : value instanceof Double number ? String.format(Locale.ROOT, "%.3f", number) : value.toString();
    }

}
