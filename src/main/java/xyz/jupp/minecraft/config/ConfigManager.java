package xyz.jupp.minecraft.config;

import org.bukkit.Bukkit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import xyz.jupp.minecraft.utils.Logger;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    private ConfigManager() {}

    private static JSONObject config;
    private final String configPath = Paths.get("").toAbsolutePath() + "/plugins/kpub/config.json";
    private final File configFile = new File(configPath);

    // config settings
    private static String serverMOTD = "";
    private static float tradeTax = 0.0f;
    private static float deathTax = 0.0f;
    private static float netherTransferTax = 0.0f;
    private static List<ShopItem> shopItems = new ArrayList<>();

    // singleton pattern
    private static ConfigManager instance;
    public static ConfigManager getManager() {
        return instance == null ? instance = new ConfigManager() : instance;
    }

    public boolean loadConfig() {
        Logger.console("load config ..");
        if (getConfigFile().exists()) {
            config = getJsonObjectFromFile();
            if (config == null) {
                Logger.console("failed parsing config");
                return false;
            }

            serverMOTD = getServerMOTD();
            Bukkit.getServer().setMotd(serverMOTD);

            tradeTax = (float) config.optDouble("tradeTax", 0.0);
            netherTransferTax = (float) config.optDouble("netherTransferTax", 0.0);
            deathTax = (float) config.optDouble("deathTax", 0.0);

            JSONArray jsonShopItems = config.optJSONArray("shopItems");
            if (jsonShopItems != null) {
                shopItems.clear();
                for (int i = 0; i < jsonShopItems.length(); i++) {
                    JSONObject jsonItem = jsonShopItems.getJSONObject(i);
                    String name = jsonItem.optString("name", "");
                    int price = jsonItem.optInt("price", 0);
                    boolean sell = jsonItem.optBoolean("sell", false);
                    int amount = jsonItem.optInt("amount", 0);
                    String description = jsonItem.optString("description", "");
                    String material = jsonItem.optString("material", "");
                    shopItems.add(new ShopItem(name, material, price, sell, amount, description));
                }
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

    private JSONObject getJsonObjectFromFile() {
        try (BufferedReader reader = new BufferedReader(new FileReader(configPath))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    private File getConfigFile() {
        return configFile;
    }

    public JSONObject getConfig() {
        return config;
    }

    public String getServerMOTD() {
        if (serverMOTD.isEmpty()) {
            serverMOTD = config.optString("serverMOTD", "").replace("&", "§");
        }
        return serverMOTD;
    }

    public float getTradeTax() { return tradeTax; }
    public float getNetherTransferTax() { return netherTransferTax; }
    public float getDeathTax() { return deathTax; }

    public static ConfigManager getInstance() { return instance; }
    public static List<ShopItem> getShopItems() {
        return shopItems;
    }
}
