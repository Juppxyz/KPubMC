package xyz.jupp.minecraft;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.jupp.minecraft.cache.WarpCache;
import xyz.jupp.minecraft.commands.*;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.MongoDB;
import xyz.jupp.minecraft.listener.*;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.PlayerUpdaterTask;

public final class Main extends JavaPlugin {

    private final static String chatPrefix = "§8[§6KlotzscherPub§8] §f";
    private final static String shopVillagerName = "§a§lHändler";
    private final static String financeVillagerFredName = "§5§lBasil";
    private final static String jewelerVillagerName = "§b§lHondo";
    private final static String blackMarketDealerVillagerName = "§8§lMorpheus";
    private final static String teamPointsDealerVillagerName = "§6§lNomad der Punktemakler";

    private final static String consolePrefix = "[KPubMC] ";
    private final static String version = "v2.0.0";
    private final static String currencyName = "Schilling";
    private final static String teamName = "§aTeam";

    private final static String inJailPrefix = "§c§lJ §8| ";
    private final static String isWantedPrefix = "§c§lW §8| ";

    private final static int teamLevelMultiple = 5000;

    // for static Access
    private static Main instance;

    private void registerCommands() {
        Bukkit.getConsoleSender().sendMessage("register commands..");
        this.getCommand("geld").setExecutor(new MoneyCommand());
        this.getCommand("money").setExecutor(new MoneyCommand());
        this.getCommand("schilling").setExecutor(new MoneyCommand());
        this.getCommand("config").setExecutor(new ConfigCommand());
        this.getCommand("hilfe").setExecutor(new HelpCommand());
        this.getCommand("help").setExecutor(new HelpCommand());
        this.getCommand("sc").setExecutor(new SlimeChunkCommand());
        this.getCommand("slimechunk").setExecutor(new SlimeChunkCommand());
        this.getCommand("einladungen").setExecutor(new InvitesCommand());
        this.getCommand("invites").setExecutor(new InvitesCommand());
        this.getCommand("team").setExecutor(new TeamCommand());
        this.getCommand("ranking").setExecutor(new RankingCommand());
        this.getCommand("hover").setExecutor(new HoverTextCommand());
        this.getCommand("kopf").setExecutor(new PlayerHeadsCommand());
        this.getCommand("head").setExecutor(new PlayerHeadsCommand());
        this.getCommand("warp").setExecutor(new WarpCommand());
        this.getCommand("customItem").setExecutor(new ItemCommand());
        this.getCommand("spenden").setExecutor(new DonateCommand());
        this.getCommand("donate").setExecutor(new DonateCommand());
        this.getCommand("spawn").setExecutor(new SpawnCommand());
        this.getCommand("rules").setExecutor(new RulesCommand());
        this.getCommand("regeln").setExecutor(new RulesCommand());
        this.getCommand("spec").setExecutor(new SpecCommand());
        this.getCommand("ec").setExecutor(new EnderchestCommand());
        this.getCommand("createshop").setExecutor(new CreateShopCommand());
        this.getCommand("createbankier").setExecutor(new CreateBankierCommand());
        this.getCommand("enderchest").setExecutor(new EnderchestCommand());
        this.getCommand("createjuweler").setExecutor(new CreateJewelerCommand());
        this.getCommand("createdealer").setExecutor(new CreateBlackMarketDealerCommand());
        this.getCommand("createtpdealer").setExecutor(new CreateTPDealerCommand());
        this.getCommand("dummy").setExecutor(new CreateDummyEntityCommand());
        this.getCommand("bestrafung").setExecutor(new JailCommand());
        this.getCommand("wanted").setExecutor(new WantedCommand());
        this.getCommand("ursprung").setExecutor(new NullpointCommand());
        this.getCommand("origin").setExecutor(new NullpointCommand());
        this.getCommand("removechunk").setExecutor(new RemoveChunkCommand());
        this.getCommand("debug").setExecutor(new MonsterEventCommand());

    }

    private void registerListener() {
        Logger.console("register listener..");
        Bukkit.getPluginManager().registerEvents(new MoneyInventoryListener(), this);
        Bukkit.getPluginManager().registerEvents(new JoinQuitListener(), this);
        Bukkit.getPluginManager().registerEvents(new ChatListener(), this);
        Bukkit.getPluginManager().registerEvents(new CommandBlockListener(), this);
        Bukkit.getPluginManager().registerEvents(new TeamInventoryListener(), this);
        Bukkit.getPluginManager().registerEvents(new NetherTransferListener(), this);
        Bukkit.getPluginManager().registerEvents(new DeathListener(), this);
        Bukkit.getPluginManager().registerEvents(new ShopListener(), this);
        Bukkit.getPluginManager().registerEvents(new WarpInventoryListener(), this);
        Bukkit.getPluginManager().registerEvents(new CreateLocalShopListener(), this);
        Bukkit.getPluginManager().registerEvents(new MobLimiterListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerMovementListener(), this);
        Bukkit.getPluginManager().registerEvents(new TeamExpListener(), this);
        Bukkit.getPluginManager().registerEvents(new CustomToolsListener(), this);
        Bukkit.getPluginManager().registerEvents(new EnderDragonListener(), this);
        Bukkit.getPluginManager().registerEvents(new SpawnListener(), this);
        Bukkit.getPluginManager().registerEvents(new TeamAreaListener(), this);

        //Bukkit.getPluginManager().registerEvents(new SwordListener(), this);
        //Bukkit.getPluginManager().registerEvents(new ElytraFlyListener(), this);
        //Bukkit.getPluginManager().registerEvents(new TeamBlockListener(), this);
    }

    private void registerTasks() {
        PlayerUpdaterTask playerListUpdate = new PlayerUpdaterTask();
        Bukkit.getConsoleSender().sendMessage(consolePrefix + "§fregister tasks ..");
        playerListUpdate.startTask();
    }


    @Override
    public void onEnable() {
        instance = this;
        Logger.console("running kpub-system..");
        Logger.console("load config..");
        ConfigManager.getManager().loadConfig();
        Logger.console("connecting to database..");
        Logger.console("init warps..");
        WarpCache.getInstance().getWarpCache();
        MongoDB.getInstance();
        registerCommands();
        registerListener();
        registerTasks();

        JailHandler.initJails(Locations.getJailCorner1(), Locations.getJailCorner2());
        JailHandler.startJailWatcherTask();

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }


    // Getter
    public static Main getInstance() {return instance;}

    public static int getTeamLevelMultiple() {return teamLevelMultiple;}

    public static String getVersion() {return version;}
    public static String getTeamName() {return teamName;}
    public static String getChatPrefix() {return chatPrefix;}
    public static String getCurrencyName() {return currencyName;}
    public static String getConsolePrefix() {return consolePrefix;}
    public static String getShopVillagerName() {return shopVillagerName;}
    public static String getJewelerVillagerName() {return jewelerVillagerName;}
    public static String getFinanceVillagerFredName() {return financeVillagerFredName;}
    public static String getTeamPointsDealerVillagerName() {return teamPointsDealerVillagerName;}
    public static String getBlackMarketDealerVillagerName() {return blackMarketDealerVillagerName;}
    public static String getCurrencyName(int amount)  {return String.format("§a%d %s", amount, currencyName);}

    // Getter for jail
    public static String getInJailPrefix() {
        return inJailPrefix;
    }
    public static String getIsWantedPrefix() {
        return isWantedPrefix;
    }
}
