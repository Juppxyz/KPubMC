package xyz.jupp.minecraft.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    private ConfigManager() {}

    private static JsonObject config;
    private final Path configPath = Paths.get("").toAbsolutePath().resolve("plugins/kpub/config.json");

    // config settings (read from async tasks, therefore volatile)
    private static volatile String serverMOTD = "";
    private static volatile float tradeTax = 0.0f;
    private static volatile float deathTax = 0.0f;
    private static volatile float netherTransferTax = 0.0f;
    private static volatile String databaseUrl = "";
    // immutable list, replaced as a whole on reload
    private static volatile List<ShopItem> shopItems = List.of();

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
            netherTransferTax = (float) optDouble(config, "netherTransferTax", 0.0);
            deathTax = (float) optDouble(config, "deathTax", 0.0);
            databaseUrl = optString(config, "databaseUrl", "");

            JsonElement jsonShopItems = config.get("shopItems");
            if (jsonShopItems != null && jsonShopItems.isJsonArray()) {
                List<ShopItem> loadedShopItems = new ArrayList<>();
                for (JsonElement element : jsonShopItems.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        Main.getInstance().getSLF4JLogger().warn("Skipping invalid shop item in config: {}", element);
                        continue;
                    }
                    JsonObject jsonItem = element.getAsJsonObject();
                    String name = optString(jsonItem, "name", "");
                    int price = optInt(jsonItem, "price", 0);
                    boolean sell = optBoolean(jsonItem, "sell", false);
                    int amount = optInt(jsonItem, "amount", 0);
                    String description = optString(jsonItem, "description", "");
                    String material = optString(jsonItem, "material", "");
                    loadedShopItems.add(new ShopItem(name, material, price, sell, amount, description));
                }
                shopItems = List.copyOf(loadedShopItems);
            }

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
    public float getNetherTransferTax() { return netherTransferTax; }
    public float getDeathTax() { return deathTax; }
    public String getDatabaseUrl() { return databaseUrl; }

    public static List<ShopItem> getShopItems() {
        return shopItems;
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

    private static boolean optBoolean(JsonObject object, String key, boolean defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return defaultValue;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isString()) {
            if ("true".equalsIgnoreCase(primitive.getAsString())) return true;
            if ("false".equalsIgnoreCase(primitive.getAsString())) return false;
        }
        return defaultValue;
    }

    private static String optString(JsonObject object, String key, String defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }
}
