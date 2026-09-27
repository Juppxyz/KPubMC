package xyz.jupp.minecraft.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.TaxBracket;
import xyz.jupp.minecraft.utils.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ConfigManager {
    private ConfigManager() {}

    private static JsonObject config;
    private final Path configPath = Paths.get("").toAbsolutePath().resolve("plugins/kpub/config.json");

    // config settings (read from async tasks, therefore volatile)
    private static volatile String serverMOTD = "";
    private static volatile float tradeTax = 0.0f;
    private static volatile float deathTax = 0.0f;
    private static volatile String databaseUrl = "";
    // market
    private static volatile int dailyOfferCount = 7;
    private static volatile double dailyDiscount = 0.15;
    private static volatile double demandHalfLifeHours = 6.0;
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

            tradeTax = (float) optDouble(config, "tradeTax", 0.0);
            deathTax = (float) optDouble(config, "deathTax", 0.0);
            databaseUrl = optString(config, "databaseUrl", "");
            dailyOfferCount = Math.max(0, optInt(config, "dailyOffers", 7));
            dailyDiscount = Math.min(0.9, Math.max(0.0, optDouble(config, "dailyDiscount", 0.15)));
            demandHalfLifeHours = Math.max(0.1, optDouble(config, "demandHalfLifeHours", 6.0));
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

    public float getTradeTax() { return tradeTax; }
    public float getDeathTax() { return deathTax; }
    public String getDatabaseUrl() { return databaseUrl; }
    public int getDailyOfferCount() { return dailyOfferCount; }
    public double getDailyDiscount() { return dailyDiscount; }
    public double getDemandHalfLifeHours() { return demandHalfLifeHours; }
    public List<TaxBracket> getNetherTransferTaxBrackets() { return netherTransferTaxBrackets; }

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
