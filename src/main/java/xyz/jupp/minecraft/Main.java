package xyz.jupp.minecraft;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitWorker;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.WarpCache;
import xyz.jupp.minecraft.commands.*;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.economy.Economy;
import xyz.jupp.minecraft.economy.Market;
import xyz.jupp.minecraft.economy.Nomad;
import xyz.jupp.minecraft.economy.Services;
import xyz.jupp.minecraft.economy.ShopViewListener;
import xyz.jupp.minecraft.economy.Treasury;
import xyz.jupp.minecraft.listener.*;
import xyz.jupp.minecraft.utils.*;

public final class Main extends JavaPlugin {

    private final static String chatPrefix = "§8[§6KlotzscherPub§8] §f";
    private final static String shopVillagerName = "§a§lHändler";
    private final static String financeVillagerFredName = "§5§lBasil";
    private final static String jewelerVillagerName = "§b§lHondo";
    private final static String blackMarketDealerVillagerName = "§8§lMorpheus";
    private final static String teamPointsDealerVillagerName = "§6§lNomad der Punktemakler";

    private final static String currencyName = "Schilling";
    private final static String teamName = "§aTeam";

    private final static String inJailPrefix = "§c§lJ §8| ";
    private final static String isWantedPrefix = "§c§lW §8| ";

    private final static int teamLevelMultiple = 5000;

    // for static Access
    private static Main instance;

    // aliases are declared in plugin.yml
    private void registerCommands() {
        Logger.console("register commands..");
        this.getCommand("money").setExecutor(new MoneyCommand());
        this.getCommand("config").setExecutor(new ConfigCommand());
        this.getCommand("help").setExecutor(new HelpCommand());
        this.getCommand("slimechunk").setExecutor(new SlimeChunkCommand());
        this.getCommand("invites").setExecutor(new InvitesCommand());
        this.getCommand("team").setExecutor(new TeamCommand());
        this.getCommand("ranking").setExecutor(new RankingCommand());
        this.getCommand("hover").setExecutor(new HoverTextCommand());
        this.getCommand("head").setExecutor(new PlayerHeadsCommand());
        this.getCommand("warp").setExecutor(new WarpCommand());
        this.getCommand("customItem").setExecutor(new ItemCommand());
        this.getCommand("donate").setExecutor(new DonateCommand());
        this.getCommand("spawn").setExecutor(new SpawnCommand());
        this.getCommand("rules").setExecutor(new RulesCommand());
        this.getCommand("spec").setExecutor(new SpecCommand());
        this.getCommand("enderchest").setExecutor(new EnderchestCommand());
        this.getCommand("createshop").setExecutor(new CreateShopCommand());
        this.getCommand("createbankier").setExecutor(new CreateBankierCommand());
        this.getCommand("createjuweler").setExecutor(new CreateJewelerCommand());
        this.getCommand("createdealer").setExecutor(new CreateBlackMarketDealerCommand());
        this.getCommand("createtpdealer").setExecutor(new CreateTPDealerCommand());
        this.getCommand("dummy").setExecutor(new CreateDummyEntityCommand());
        this.getCommand("bestrafung").setExecutor(new JailCommand());
        this.getCommand("wanted").setExecutor(new WantedCommand());
        this.getCommand("origin").setExecutor(new NullpointCommand());
        this.getCommand("removechunk").setExecutor(new RemoveChunkCommand());
        this.getCommand("staatskasse").setExecutor(new TreasuryCommand());
        this.getCommand("shopadmin").setExecutor(new ShopAdminCommand());
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
        Bukkit.getPluginManager().registerEvents(new ShopViewListener(), this);
        Bukkit.getPluginManager().registerEvents(new WarpInventoryListener(), this);
        Bukkit.getPluginManager().registerEvents(new CreateLocalShopListener(), this);
        Bukkit.getPluginManager().registerEvents(new MobLimiterListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerMovementListener(), this);
        Bukkit.getPluginManager().registerEvents(new TeamExpListener(), this);
        Bukkit.getPluginManager().registerEvents(new CustomToolsListener(), this);
        Bukkit.getPluginManager().registerEvents(new EnderDragonListener(), this);
        Bukkit.getPluginManager().registerEvents(new SpawnListener(), this);
        Bukkit.getPluginManager().registerEvents(new TeamAreaListener(), this);
        Bukkit.getPluginManager().registerEvents(new AntiBugListener(), this);

        // afk
        Bukkit.getPluginManager().registerEvents(new AfkListener(), this);
    }

    private void registerTasks() {
        Logger.console("register tasks..");
        new PlayerUpdaterTask().startTask();
        Market.startTasks();
    }


    @Override
    public void onEnable() {
        instance = this;
        AfkHelper.tickKickTask().runTaskTimer(this, 20L, 20L);

        Logger.console("running kpub-system..");
        Logger.console("load config..");
        ConfigManager.getManager().loadConfig();
        Logger.console("connecting to database..");
        try {
            Database.connect();
        } catch (RuntimeException e) {
            getSLF4JLogger().error(e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Logger.console("init warps..");
        int warps = WarpCache.getInstance().load();
        int claimedChunks = ChunkCache.getInstance().load();
        Logger.console("loaded " + warps + " warps and " + claimedChunks + " claimed chunks");
        Logger.console("init market..");
        Treasury.load();
        Economy.load();
        Market.load();
        Services.load();
        Nomad.load();
        Logger.console("loaded " + Market.all().size() + " market items, " + Market.dailyOffers().size() + " daily offers");
        registerCommands();
        registerListener();
        registerTasks();

        JailHandler.initJails(Locations.getJailCorner1(), Locations.getJailCorner2());
        JailHandler.startJailWatcherTask();
    }

    // Running async workers (e.g. a money transfer between withdraw and credit) are not interrupted by Bukkit,
    // so the client is only closed once they are done (bounded, Paper itself waits 5 s for them after onDisable).
    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        awaitRunningWorkers(5_000L);
        Database.close();
    }

    private void awaitRunningWorkers(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (hasRunningWorkers() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean hasRunningWorkers() {
        for (BukkitWorker worker : getServer().getScheduler().getActiveWorkers()) {
            if (worker.getOwner() == this && worker.getThread() != Thread.currentThread()) return true;
        }
        return false;
    }


    // Getter
    public static Main getInstance() {return instance;}

    public static int getTeamLevelMultiple() {return teamLevelMultiple;}

    public static String getTeamName() {return teamName;}
    public static String getChatPrefix() {return chatPrefix;}
    public static String getCurrencyName() {return currencyName;}
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
