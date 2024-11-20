package xyz.jupp.minecraft.config;

import org.bukkit.Bukkit;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    private ConfigManager(){}


    private static JSONObject config;
    private final String configPath = Paths.get("").toAbsolutePath() + "/plugins/kpub/config.json";
    private final File configFile = new File(configPath);


    // config settings
    private static String serverMOTD = "";
    private static float tradeTax = 0.0f;
    private static float deathTax = 0.0f;
    private static float netherTransferTax = 0.0f;
    private static List<ShopItem> shopItems = new ArrayList<>();


    // single pattern
    private static ConfigManager instance;
    public static ConfigManager getManager() {
        return instance == null ? instance = new ConfigManager() : instance;
    }


    public boolean loadConfig() {
        Logger.console("load config ..");
        if (getConfigFile().exists()) {
            config = getJsonObjectFromFile();
            serverMOTD = getServerMOTD();
            Bukkit.getServer().setMotd(serverMOTD);

            Number tradeTaxNumber = (Number) config.get("tradeTax");
            Number netherTransferTaxNumber = (Number) config.get("netherTransferTax");
            Number deathTaxNumber = (Number) config.get("deathTax");

            tradeTax = tradeTaxNumber.floatValue();
            netherTransferTax = netherTransferTaxNumber.floatValue();
            deathTax = deathTaxNumber.floatValue();

            JSONArray jsonShopItems = (JSONArray) config.get("shopItems");
            if (jsonShopItems != null) {
                shopItems.clear();
                for (Object obj : jsonShopItems) {
                    JSONObject jsonItem = (JSONObject) obj;
                    String name = (String) jsonItem.get("name");
                    long price = (Long) jsonItem.get("price");
                    boolean sell = (Boolean) jsonItem.get("sell");
                    long amount = (Long) jsonItem.get("amount");
                    String description = (String) jsonItem.get("description");
                    String material = (String) jsonItem.get("material");
                    shopItems.add(new ShopItem(name, material,(int)price, sell, (int)amount, description));
                }
            }
            Logger.console("loaded config successfully");
            return true;
        }
        Logger.console("failed loading config");
        return false;
    }


    // this is a simple wrapper for better logging
    public void updateConfig() {
        Logger.console( "update config..");
        loadConfig();
        Logger.console("updated config.");
    }


    private JSONObject getJsonObjectFromFile() {
        JSONParser jsonParser = new JSONParser();
        JSONObject jsonObject = null;
        FileReader fileReader;
        try {
            fileReader = new FileReader(configPath);
            jsonObject = (JSONObject) jsonParser.parse(fileReader);
            fileReader.close();
        } catch (IOException | ParseException e) {
            e.printStackTrace();
            return null;
        }
        return jsonObject;
    }

    private File getConfigFile() {
        return configFile;
    }

    public JSONObject getConfig() {
        return config;
    }

    public String getServerMOTD() {
        if (serverMOTD.isEmpty()) {
            serverMOTD = config.getOrDefault("serverMOTD", "").toString().replace("&", "§");
        }
        return serverMOTD;
    }


    public float getTradeTax() {return tradeTax;}
    public float getNetherTransferTax() {return netherTransferTax;}
    public float getDeathTax() {return deathTax;}

    public static ConfigManager getInstance() {return instance;}

    public static List<ShopItem> getShopItems() {
        return shopItems;
    }
}
