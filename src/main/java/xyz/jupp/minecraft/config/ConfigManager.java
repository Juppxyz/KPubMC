package xyz.jupp.minecraft.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.TaxBracket;
import xyz.jupp.minecraft.economy.TaxClass;
import xyz.jupp.minecraft.utils.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {
    private ConfigManager() {}

    private static JsonObject config;
    private final Path configPath = Paths.get("").toAbsolutePath().resolve("plugins/kpub/config.json");

    // config settings (read from async tasks, therefore volatile)
    private static volatile String serverMOTD = "";
    // base rates per tax class (the economy factor scales them); immutable map, replaced as a whole on reload
    private static volatile Map<TaxClass, Double> taxClassRates = defaultTaxClassRates(null);
    private static volatile float deathTax = 0.0f;
    private static volatile String databaseUrl = "";
    // market
    private static volatile int dailyOfferCount = 7;
    private static volatile double dailyDiscount = 0.15;
    private static volatile double demandHalfLifeHours = 6.0;
    // economy automatic and AI review
    private static volatile double economyFactorMin = 0.7;
    private static volatile double economyFactorMax = 1.3;
    private static volatile double economyTargetGrowth = 0.02;
    private static volatile int treasuryTargetPerPlayer = 20_000;
    private static volatile boolean aiReview = true;
    private static volatile String openAiModel = "gpt-5-mini";
    private static volatile String openAiApiKey = "";
    private static volatile LocalDateTime endUnlock = null;
    // progressive nether transfer tax, sorted by 'from'; immutable list, replaced as a whole on reload
    private static final List<TaxBracket> DEFAULT_NETHER_BRACKETS = List.of(
            new TaxBracket(0, 0.0), new TaxBracket(500, 0.5), new TaxBracket(5_000, 0.65), new TaxBracket(20_000, 0.7));
    private static volatile List<TaxBracket> netherTransferTaxBrackets = DEFAULT_NETHER_BRACKETS;

    // singleton pattern
    private static final ConfigManager instance = new ConfigManager();
    public static ConfigManager getManager() {
        return instance;
    }

    public boolean loadConfig() {
        Logger.console("load config ..");
        if (Files.exists(configPath)) {
            JsonObject loadedConfig = readConfigFile();
            if (loadedConfig == null) {
                Logger.console("failed parsing config");
                return false;
            }
            config = loadedConfig;

            serverMOTD = getServerMOTD();
            Bukkit.getServer().setMotd(serverMOTD);

            taxClassRates = parseTaxClasses(config.get("taxClasses"), optNumber(config, "tradeTax"));
            deathTax = (float) optDouble(config, "deathTax", 0.0);
            databaseUrl = optString(config, "databaseUrl", "");
            dailyOfferCount = Math.max(0, optInt(config, "dailyOffers", 7));
            dailyDiscount = Math.min(0.9, Math.max(0.0, optDouble(config, "dailyDiscount", 0.15)));
            demandHalfLifeHours = Math.max(0.1, optDouble(config, "demandHalfLifeHours", 6.0));
            economyFactorMin = Math.max(0.1, optDouble(config, "economyFactorMin", 0.7));
            economyFactorMax = Math.max(economyFactorMin, optDouble(config, "economyFactorMax", 1.3));
            economyTargetGrowth = optDouble(config, "economyTargetGrowth", 0.02);
            treasuryTargetPerPlayer = Math.max(0, optInt(config, "treasuryTargetPerPlayer", 20_000));
            aiReview = !"false".equalsIgnoreCase(optString(config, "aiReview", "true"));
            openAiModel = optString(config, "openAiModel", "gpt-5-mini");
            openAiApiKey = optString(config, "openAiApiKey", "").trim();
            endUnlock = parseDateTime(optString(config, "endUnlock", ""));
            netherTransferTaxBrackets = parseBrackets(config.get("netherTransferTaxBrackets"));

            Logger.console("loaded config successfully");
            return true;
        }
        Logger.console("failed loading config");
        return false;
    }

    public void updateConfig() {
        Logger.console("update config..");
        loadConfig();
        Logger.console("updated config.");
    }

    private JsonObject readConfigFile() {
        try {
            // line breaks are dropped like the former line-by-line reader did
            String content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8)
                    .replace("\r", "")
                    .replace("\n", "");
            JsonElement element = JsonParser.parseString(content);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            Main.getInstance().getSLF4JLogger().error("{} does not contain a JSON object", configPath);
        } catch (IOException | JsonParseException e) {
            Main.getInstance().getSLF4JLogger().error("Could not read {}", configPath, e);
        }
        return null;
    }

    // Once set, the MOTD is kept until the next restart (a reload does not change it).
    public String getServerMOTD() {
        if (serverMOTD.isEmpty() && config != null) {
            serverMOTD = optString(config, "serverMOTD", "").replace("&", "§");
        }
        return serverMOTD;
    }

    public double getTaxClassRate(TaxClass taxClass) { return taxClassRates.get(taxClass); }
    public float getDeathTax() { return deathTax; }
    public String getDatabaseUrl() { return databaseUrl; }
    public int getDailyOfferCount() { return dailyOfferCount; }
    public double getDailyDiscount() { return dailyDiscount; }
    public double getDemandHalfLifeHours() { return demandHalfLifeHours; }
    public List<TaxBracket> getNetherTransferTaxBrackets() { return netherTransferTaxBrackets; }
    public double getEconomyFactorMin() { return economyFactorMin; }
    public double getEconomyFactorMax() { return economyFactorMax; }
    public double getEconomyTargetGrowth() { return economyTargetGrowth; }
    public int getTreasuryTargetPerPlayer() { return treasuryTargetPerPlayer; }
    public boolean isAiReview() { return aiReview; }
    public String getOpenAiModel() { return openAiModel; }
    public String getOpenAiApiKey() { return openAiApiKey; }
    public LocalDateTime getEndUnlock() { return endUnlock; }

    // "2026-10-10T16:00" or "2026-10-10"; empty or invalid -> null
    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return value.contains("T") ? LocalDateTime.parse(value.trim()) : LocalDate.parse(value.trim()).atStartOfDay();
        } catch (DateTimeParseException e) {
            Main.getInstance().getSLF4JLogger().warn("Invalid endUnlock '{}', the End stays open", value);
            return null;
        }
    }

    // {"BASIC": 0.10, "STANDARD": 0.20, "LUXURY": 0.35}; missing classes keep their default,
    // an old flat "tradeTax" becomes the standard rate
    private static Map<TaxClass, Double> parseTaxClasses(JsonElement element, Number legacyTradeTax) {
        Map<TaxClass, Double> rates = defaultTaxClassRates(legacyTradeTax);
        if (element != null && element.isJsonObject()) {
            for (TaxClass taxClass : TaxClass.values()) {
                double rate = optDouble(element.getAsJsonObject(), taxClass.name(), -1);
                if (rate >= 0 && rate <= 0.9) rates.put(taxClass, rate);
            }
        }
        return Map.copyOf(rates);
    }

    private static Map<TaxClass, Double> defaultTaxClassRates(Number legacyTradeTax) {
        Map<TaxClass, Double> rates = new EnumMap<>(TaxClass.class);
        for (TaxClass taxClass : TaxClass.values()) rates.put(taxClass, taxClass.defaultRate());
        if (legacyTradeTax != null) rates.put(TaxClass.STANDARD, legacyTradeTax.doubleValue());
        return rates;
    }

    // [{"from": 0, "rate": 0.0}, {"from": 500, "rate": 0.5}, ...]; missing or broken -> the default brackets
    private static List<TaxBracket> parseBrackets(JsonElement element) {
        if (element == null || !element.isJsonArray()) return DEFAULT_NETHER_BRACKETS;
        List<TaxBracket> brackets = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject bracket = entry.getAsJsonObject();
            double rate = optDouble(bracket, "rate", -1);
            int from = optInt(bracket, "from", -1);
            if (from < 0 || rate < 0 || rate > 1) {
                Main.getInstance().getSLF4JLogger().warn("Skipping invalid nether tax bracket {}", entry);
                continue;
            }
            brackets.add(new TaxBracket(from, rate));
        }
        if (brackets.isEmpty()) return DEFAULT_NETHER_BRACKETS;
        brackets.sort(Comparator.comparingInt(TaxBracket::from));
        return List.copyOf(brackets);
    }


    // lenient accessors with the same semantics as the former org.json opt* methods

    private static Number optNumber(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) return primitive.getAsNumber();
        if (primitive.isString()) {
            try {
                return new BigDecimal(primitive.getAsString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static double optDouble(JsonObject object, String key, double defaultValue) {
        Number number = optNumber(object, key);
        return number == null ? defaultValue : number.doubleValue();
    }

    private static int optInt(JsonObject object, String key, int defaultValue) {
        Number number = optNumber(object, key);
        return number == null ? defaultValue : number.intValue();
    }

    private static String optString(JsonObject object, String key, String defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }
}
