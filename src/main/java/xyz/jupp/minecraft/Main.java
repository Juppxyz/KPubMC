package xyz.jupp.minecraft;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.jupp.minecraft.cache.WarpCache;
import xyz.jupp.minecraft.commands.*;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.MongoDB;
import xyz.jupp.minecraft.listener.*;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.PlayerUpdaterTask;

public final class Main extends JavaPlugin {

    private final static String chatPrefix = "§8[§6KlotzscherPub§8] §f";
    private final static String shopVillagerName = "§a§lHändler";

    private final static String consolePrefix = "[KPubMC] ";
    private final static String version = "v0.0.1";
    private final static String currencyName = "Schilling";
    private final static String teamName = "§aTeam";

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
        this.getCommand("createshop").setExecutor(new CreateShopCommand());
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
        this.getCommand("createbankier").setExecutor(new CreateBankierCommand());
        this.getCommand("spec").setExecutor(new SpecCommand());

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
        Bukkit.getPluginManager().registerEvents(new SwordListener(), this);
        Bukkit.getPluginManager().registerEvents(new CreateLocalShopListener(), this);
        Bukkit.getPluginManager().registerEvents(new ElytraFlyListener(), this);
        Bukkit.getPluginManager().registerEvents(new MobLimiterListener(), this);
        Bukkit.getPluginManager().registerEvents(new TeamBlockListener(), this);
        Bukkit.getPluginManager().registerEvents(new EnderDragonListener(), this);
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
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }


    // Getter
    public static Main getInstance() {return instance;}

    public static String getVersion() {return version;}
    public static String getChatPrefix() {return chatPrefix;}
    public static String getConsolePrefix() {return consolePrefix;}
    public static String getCurrencyName() {return currencyName;}
    public static String getCurrencyName(int amount)  {return String.format("§a%d %s", amount, currencyName);}
    public static String getTeamName() {return teamName;}
    public static String getShopVillagerName() {return shopVillagerName;}
}
