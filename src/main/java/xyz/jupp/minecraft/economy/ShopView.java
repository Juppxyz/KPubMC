package xyz.jupp.minecraft.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shop GUI of the "Händler".
 * <p>
 * Overview: one tab per category in the top row (the active one is marked below and named in the title), the goods in
 * the middle. A click on a good only opens its detail page; buying and selling happen there with explicit quantity
 * buttons, so stray clicks (e.g. from scroll mods) in the overview can never buy anything.
 * All state lives in this holder and is only used on the main thread.
 */
public final class ShopView implements InventoryHolder {

    public enum Tab {
        DAILY("§6Tagesangebote", Material.SUNFLOWER, null),
        FARMING(Category.FARMING),
        BLOCKS(Category.BLOCKS),
        ORES(Category.ORES),
        FOOD(Category.FOOD),
        MISC(Category.MISC),
        INFO("§fSo funktioniert's", Material.BOOK, null);

        private final String label;
        private final Material icon;
        private final @Nullable Category category;

        Tab(Category category) {
            this(category.label(), category.icon(), category);
        }

        Tab(String label, Material icon, @Nullable Category category) {
            this.label = label;
            this.icon = icon;
            this.category = category;
        }
    }

    private enum Page { OVERVIEW, DETAIL, RANDOM }

    private record Action(boolean buy, int bundles) {}

    private static final int SIZE = 54;
    private static final int SLOT_CLOSE = 8;
    private static final int MARKER_ROW = 9;
    private static final int CONTENT_START = 18;
    private static final int CONTENT_SIZE = 27;
    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_RANDOM = 47;
    private static final int SLOT_BALANCE = 49;
    private static final int SLOT_TREASURY = 51;
    private static final int SLOT_NEXT = 53;
    // detail page
    private static final int SLOT_BACK = 0;
    private static final int SLOT_GOOD = 4;
    private static final int SLOT_BUY_LABEL = 18;
    private static final int SLOT_SELL_LABEL = 27;
    // one centred button, or up to three: 1 Paket, 1 Stack, Max
    private static final int[] BUY_SLOTS = {21, 22, 23};
    private static final int[] SELL_SLOTS = {30, 32};
    private static final int CENTER_BUY_SLOT = 22;
    private static final int CENTER_SELL_SLOT = 31;
    // changed behaviour: for years spawn eggs could change spawners, now they cannot (see AntiBugListener)
    private static final String SPAWN_EGG_WARNING = "§c⚠ Kann keine Spawner umstellen!";

    // clicks right after a re-render are ignored (double clicks, scroll mods)
    private static final long CLICK_COOLDOWN_MILLIS = 300;

    private final Player viewer;
    private final Map<Integer, Material> contentSlots = new HashMap<>();
    private final Map<Integer, Action> actions = new HashMap<>();
    private Inventory inventory;
    private Tab tab = Tab.DAILY;
    private int page;
    private Page view = Page.OVERVIEW;
    private @Nullable Material detail;
    private int balance;
    private boolean busy;
    private long ignoreClicksUntil;

    private ShopView(Player viewer, int balance) {
        this.viewer = viewer;
        this.balance = balance;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }


    /* open / refresh (main thread) */

    public static void open(@NotNull Player player) {
        Tasks.supplyAsync(() -> PlayerRepository.getMoney(player), money -> {
            if (!player.isOnline()) return;
            ShopView shop = new ShopView(player, money);
            shop.reopen();
            player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 2f, 2f);
        });
    }

    /** Re-renders every open shop in place, e.g. after a trade or a price decay. */
    public static void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ShopView shop) shop.render();
        }
    }

    // a new window, because the title (current tab or good) cannot be changed on an open inventory
    private void reopen() {
        inventory = Bukkit.createInventory(this, SIZE, title());
        render();
        viewer.openInventory(inventory);
    }

    private Component title() {
        Component prefix = Text.section("§8§lHändler §8» ");
        return switch (view) {
            case OVERVIEW -> prefix.append(Text.section(tab.label));
            case RANDOM -> prefix.append(Text.section("§5Zufall"));
            case DETAIL -> {
                MarketItem item = detail == null ? null : Market.get(detail);
                yield item == null ? prefix : prefix.append(name(item));
            }
        };
    }

    private void render() {
        inventory.clear();
        contentSlots.clear();
        actions.clear();
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);

        switch (view) {
            case OVERVIEW -> renderOverview();
            case DETAIL -> renderDetail();
            case RANDOM -> renderRandom();
        }

        inventory.setItem(SLOT_BALANCE, named(Material.GOLD_NUGGET, "§fDein Konto: " + Main.getCurrencyName(balance), List.of()));
        inventory.setItem(SLOT_TREASURY, named(Material.GOLD_BLOCK, "§6Staatskasse: " + Main.getCurrencyName((int) Math.min(Integer.MAX_VALUE, Treasury.balance())),
                List.of("§7Hier landen alle Steuern.",
                        "§7Steuern gerade: " + Economy.levelWord(),
                        "§8/staatskasse")));
    }


    /* overview */

    private void renderOverview() {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) inventory.setItem(i, tabItem(tabs[i]));
        inventory.setItem(SLOT_CLOSE, named(Material.BARRIER, "§cSchließen", List.of()));
        inventory.setItem(MARKER_ROW + tab.ordinal(), named(Material.LIME_STAINED_GLASS_PANE, "§a▲ " + tab.label, List.of("§7Aktuelle Kategorie")));

        if (tab == Tab.INFO) {
            renderInfo();
            return;
        }
        List<MarketItem> goods = goods(tab);
        int pages = Math.max(1, (goods.size() + CONTENT_SIZE - 1) / CONTENT_SIZE);
        page = Math.min(page, pages - 1);
        int slot = CONTENT_START;
        for (MarketItem item : goods.subList(page * CONTENT_SIZE, Math.min(goods.size(), (page + 1) * CONTENT_SIZE))) {
            inventory.setItem(slot, overviewItem(item));
            contentSlots.put(slot, item.material());
            slot++;
        }
        for (; slot < CONTENT_START + CONTENT_SIZE; slot++) inventory.setItem(slot, null);
        if (goods.isEmpty()) inventory.setItem(31, named(Material.BARRIER, "§7Hier gibt es gerade nichts.", List.of()));

        if (page > 0) inventory.setItem(SLOT_PREVIOUS, named(Material.ARROW, "§f◀ Vorherige Seite", List.of("§7Seite " + page + " von " + pages)));
        if (page < pages - 1) inventory.setItem(SLOT_NEXT, named(Material.ARROW, "§fNächste Seite ▶", List.of("§7Seite " + (page + 2) + " von " + pages)));
        if (tab == Tab.DAILY && Market.randomItemPrice() > 0) {
            inventory.setItem(SLOT_RANDOM, named(Material.EXPERIENCE_BOTTLE, "§5§oZufall", List.of("§7Ein zufälliges Tagesangebot,", "§7günstiger als im Schnitt.", "", "§e» Klicken für Details")));
        }
    }

    private static List<MarketItem> goods(Tab tab) {
        List<MarketItem> goods = tab == Tab.DAILY ? Market.dailyOffers() : Market.category(tab.category);
        return goods.stream().filter(item -> item.material().isItem()).toList();
    }

    private ItemStack tabItem(Tab candidate) {
        boolean selected = candidate == tab;
        List<String> lore = new ArrayList<>();
        lore.add(candidate == Tab.INFO ? "§7Preise und Steuern erklärt" : "§7Kategorie");
        if (candidate != Tab.INFO) lore.add("§8" + goods(candidate).size() + " Items");
        lore.add("");
        lore.add(selected ? "§a✔ Geöffnet" : "§e» Klicken zum Öffnen");
        ItemStack item = named(candidate.icon, (selected ? "§a§l" : "§f§l") + Text.strip(candidate.label), lore);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(selected);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack overviewItem(MarketItem item) {
        ItemStack stack = displayStack(item);
        ItemMeta meta = stack.getItemMeta();
        List<String> lore = new ArrayList<>(priceLines(item));
        lore.add("");
        lore.add("§e» Klicken zum Kaufen/Verkaufen");
        meta.lore(Text.lore(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    private List<String> priceLines(MarketItem item) {
        boolean daily = isDailyPrice(item);
        List<String> lore = new ArrayList<>();
        if (item.amount() > 1) lore.add("§7Paket: §f" + item.amount() + " Stück");
        if (item.buyable()) {
            int net = item.buyTotal(1, daily ? Market.dailyDiscount() : 0);
            int tax = Taxes.taxOn(net, item.taxClass());
            lore.add("§7Kaufen: " + Main.getCurrencyName(net + tax) + " §8(inkl. " + tax + " Steuer)");
        } else {
            lore.add("§7Kaufen: §8nicht hier" + (item.description() != null ? " §8(" + item.description() + ")" : ""));
        }
        lore.add(item.sellable() ? "§7Verkaufen: " + Main.getCurrencyName(item.sellPrice()) : "§7Verkaufen: §8wird nicht angekauft");
        String trend = trendLine(item);
        if (trend != null) lore.add(trend);
        if (daily && item.buyable()) lore.add("§6★ Tagesangebot §a-" + percent(Market.dailyDiscount()));
        if (isSpawnEgg(item.material())) {
            lore.add(SPAWN_EGG_WARNING);
        } else if (item.buyable() && item.description() != null && !item.description().isBlank()) {
            lore.add("§7§o" + item.description());
        }
        return lore;
    }

    private boolean isDailyPrice(MarketItem item) {
        return (view == Page.OVERVIEW ? tab == Tab.DAILY : Market.isDailyOffer(item.material())) && Market.isDailyOffer(item.material());
    }

    // only when the price is noticeably off its usual level
    private static @Nullable String trendLine(MarketItem item) {
        double trend = item.trend();
        if (trend >= 0.05) return "§c▲ gerade teurer als sonst";
        if (trend <= -0.05) return "§a▼ gerade günstiger als sonst";
        return null;
    }

    private void renderInfo() {
        inventory.setItem(20, named(Material.WRITABLE_BOOK, "§ePreise", List.of(
                "§7Was oft gekauft wird, wird teurer.",
                "§7Was oft verkauft wird, wird billiger.",
                "§7Nach ein paar Stunden pendeln sich",
                "§7die Preise wieder ein.")));

        List<String> taxes = new ArrayList<>(List.of(
                "§7Beim Kaufen zahlst du Steuern:",
                "§7  Essen & Farm-Sachen: §a" + percent(Taxes.rate(TaxClass.BASIC)),
                "§7  Normale Waren: §a" + percent(Taxes.rate(TaxClass.STANDARD)),
                "§7  Seltenes & Luxus: §a" + percent(Taxes.rate(TaxClass.LUXURY)),
                "§7Beim Sterben: §a" + percent(Taxes.deathRate()) + " §7deines Geldes §8(ab 250)",
                "§7Beim Nether-Portal: je mehr Geld",
                "§7du hast, desto mehr §8(bis " + percent(Taxes.topNetherRate()) + ")",
                "",
                "§7Steuern gerade: " + Economy.levelWord(),
                "§8Ist viel Geld im Umlauf, steigen sie etwas,",
                "§8sonst sinken sie wieder."));
        inventory.setItem(22, named(Material.PAPER, "§cSteuern", taxes));

        ZonedDateTime now = ZonedDateTime.now(Market.ZONE);
        Duration untilReset = Duration.between(now, LocalDate.now(Market.ZONE).plusDays(1).atStartOfDay(Market.ZONE));
        inventory.setItem(24, named(Material.CLOCK, "§6Tagesangebote", List.of(
                "§7Jeden Tag um 0 Uhr neu, mit Rabatt.",
                "§7Neue Angebote in: §f" + untilReset.toHours() + " h " + untilReset.toMinutesPart() + " min")));

        inventory.setItem(31, named(Material.GOLD_BLOCK, "§6Staatskasse", List.of(
                "§7Alle Steuern landen hier.",
                "§7Stand: " + Main.getCurrencyName((int) Math.min(Integer.MAX_VALUE, Treasury.balance())),
                "§8/staatskasse")));
    }


    /* detail page of one good */

    private void renderDetail() {
        MarketItem item = detail == null ? null : Market.get(detail);
        inventory.setItem(SLOT_BACK, named(Material.ARROW, "§f◀ Zurück", List.of("§7zu " + tab.label)));
        if (item == null || !item.enabled()) {
            inventory.setItem(22, named(Material.BARRIER, "§7Dieses Item gibt es gerade nicht.", List.of()));
            return;
        }

        int owned = countPlain(viewer, item.material());
        ItemStack good = displayStack(item);
        ItemMeta meta = good.getItemMeta();
        List<String> lore = new ArrayList<>(priceLines(item));
        lore.add("§7Im Inventar: §f" + owned + " Stück");
        meta.lore(Text.lore(lore));
        good.setItemMeta(meta);
        inventory.setItem(SLOT_GOOD, good);

        // buy row
        inventory.setItem(SLOT_BUY_LABEL, named(Material.LIME_CONCRETE, "§a§lKaufen", List.of()));
        if (!item.buyable()) {
            inventory.setItem(CENTER_BUY_SLOT, named(Material.GRAY_STAINED_GLASS_PANE, "§7Hier nicht kaufbar",
                    item.description() != null ? List.of("§7" + item.description()) : List.of()));
        } else {
            List<BuyOption> options = buyOptions(item);
            if (options.isEmpty()) {
                inventory.setItem(CENTER_BUY_SLOT, named(Material.GRAY_STAINED_GLASS_PANE, "§7Kein Platz im Inventar", List.of()));
            }
            double discount = isDailyPrice(item) ? Market.dailyDiscount() : 0;
            int[] slots = options.size() == 1 ? new int[]{CENTER_BUY_SLOT} : BUY_SLOTS;
            for (int i = 0; i < options.size(); i++) {
                BuyOption option = options.get(i);
                int quantity = option.bundles() * item.amount();
                int net = item.buyTotal(option.bundles(), discount);
                int tax = Taxes.taxOn(net, item.taxClass());
                List<String> buttonLore = new ArrayList<>();
                buttonLore.add("§7Preis: " + Main.getCurrencyName(net + tax) + " §8(inkl. " + tax + " Steuer)");
                if (net + tax > balance) buttonLore.add("§cDir fehlen " + (net + tax - balance) + " Schilling");
                buttonLore.add("");
                if (isSpawnEgg(item.material())) buttonLore.add(SPAWN_EGG_WARNING);
                buttonLore.add("§e» Linksklick zum Kaufen");
                String name = options.size() == 1
                        ? "§a§lKaufen" + (quantity > 1 ? " §8» §f" + quantity + "×" : "")
                        : "§a§l" + option.label() + " §8» §f" + quantity + "×";
                inventory.setItem(slots[i], named(Material.LIME_STAINED_GLASS_PANE, name, buttonLore));
                actions.put(slots[i], new Action(true, option.bundles()));
            }
        }

        // sell row
        inventory.setItem(SLOT_SELL_LABEL, named(Material.RED_CONCRETE, "§c§lVerkaufen", List.of("§7Nur unbenannte Items", "§7aus deinem Inventar.")));
        if (!item.sellable()) {
            inventory.setItem(SELL_SLOTS[0], named(Material.GRAY_STAINED_GLASS_PANE, "§7Wird nicht angekauft", List.of()));
            return;
        }
        int ownedBundles = owned / item.amount();
        if (ownedBundles == 0) {
            inventory.setItem(SELL_SLOTS[0], named(Material.GRAY_STAINED_GLASS_PANE, "§7Du hast nicht genug davon",
                    List.of("§7Mindestens " + item.amount() + " Stück nötig.")));
            return;
        }
        // 1 Paket and Alles (one button if both are the same)
        List<Integer> sellOptions = ownedBundles > 1 ? List.of(1, ownedBundles) : List.of(1);
        int[] slots = sellOptions.size() == 1 ? new int[]{CENTER_SELL_SLOT} : SELL_SLOTS;
        for (int i = 0; i < sellOptions.size(); i++) {
            int bundles = sellOptions.get(i);
            String name = (i == 1 ? "§c§lAlles verkaufen §8» §f" : "§c§lVerkaufen §8» §f") + bundles * item.amount() + "×";
            inventory.setItem(slots[i], named(Material.RED_STAINED_GLASS_PANE, name,
                    List.of("§7Du erhältst: " + Main.getCurrencyName(item.sellTotal(bundles)), "", "§e» Linksklick zum Verkaufen")));
            actions.put(slots[i], new Action(false, bundles));
        }
    }

    private record BuyOption(String label, int bundles) {}

    // luxury goods and single items are bought one at a time
    private static boolean isSingleBuy(MarketItem item) {
        return item.taxClass() == TaxClass.LUXURY || item.category() == Category.RARE
                || item.material().getMaxStackSize() == 1 || isSpawnEgg(item.material());
    }

    // 1 Paket, 1 Stack and Max (what fits into the inventory) without duplicates; single items only 1 Paket
    private List<BuyOption> buyOptions(MarketItem item) {
        int fitting = Math.min(64, freeSpace(viewer, item.material()) / item.amount());
        if (fitting < 1) return List.of();
        List<BuyOption> options = new ArrayList<>();
        options.add(new BuyOption("1 Paket", 1));
        if (isSingleBuy(item)) return options;
        int stack = Math.max(1, item.material().getMaxStackSize() / item.amount());
        if (stack > 1 && stack <= fitting) options.add(new BuyOption("1 Stack", stack));
        if (fitting > options.getLast().bundles()) options.add(new BuyOption("Max", fitting));
        return options;
    }

    private void renderRandom() {
        inventory.setItem(SLOT_BACK, named(Material.ARROW, "§f◀ Zurück", List.of("§7zu " + tab.label)));
        int net = Market.randomItemPrice();
        int tax = Taxes.taxOn(net, TaxClass.STANDARD);
        inventory.setItem(SLOT_GOOD, named(Material.EXPERIENCE_BOTTLE, "§5§oZufall", List.of(
                "§7Du bekommst ein zufälliges Paket",
                "§7aus den heutigen Tagesangeboten.",
                "§7Preis: " + Main.getCurrencyName(net + tax) + " §8(inkl. " + tax + " Steuer)")));
        if (net <= 0) return;
        List<String> lore = new ArrayList<>(List.of("§7Preis: " + Main.getCurrencyName(net + tax) + " §8(inkl. " + tax + " Steuer)"));
        if (net + tax > balance) lore.add("§cDir fehlen " + (net + tax - balance) + " Schilling");
        lore.add("");
        lore.add("§e» Linksklick zum Kaufen");
        inventory.setItem(CENTER_BUY_SLOT, named(Material.LIME_STAINED_GLASS_PANE, "§a§lZufall kaufen", lore));
        actions.put(CENTER_BUY_SLOT, new Action(true, 1));
    }


    /* clicks (main thread) */

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || busy) return;
        if (System.currentTimeMillis() < ignoreClicksUntil) return;
        ClickType click = event.getClick();
        // only plain clicks: no shift, double, number key, drop or swap clicks
        if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
        int slot = event.getRawSlot();

        switch (view) {
            case OVERVIEW -> clickOverview(player, slot);
            case DETAIL, RANDOM -> clickDetail(player, slot, click);
        }
    }

    private void clickOverview(Player player, int slot) {
        if (slot >= 0 && slot < Tab.values().length) {
            Tab selected = Tab.values()[slot];
            if (selected == tab) return;
            tab = selected;
            page = 0;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            Tasks.sync(this::reopen);
            return;
        }
        if (slot == SLOT_CLOSE) {
            Tasks.sync(player::closeInventory);
            return;
        }
        if (slot == SLOT_PREVIOUS && page > 0) {
            page--;
            render();
            return;
        }
        if (slot == SLOT_NEXT && isArrow(SLOT_NEXT)) {
            page++;
            render();
            return;
        }
        if (slot == SLOT_RANDOM && tab == Tab.DAILY && Market.randomItemPrice() > 0) {
            view = Page.RANDOM;
            Tasks.sync(this::reopen);
            return;
        }
        Material material = contentSlots.get(slot);
        if (material == null) return;
        view = Page.DETAIL;
        detail = material;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        Tasks.sync(this::reopen);
    }

    private void clickDetail(Player player, int slot, ClickType click) {
        if (slot == SLOT_BACK) {
            view = Page.OVERVIEW;
            detail = null;
            Tasks.sync(this::reopen);
            return;
        }
        Action action = actions.get(slot);
        // buttons only react to a left click
        if (action == null || click != ClickType.LEFT) return;
        if (view == Page.RANDOM) {
            buyRandom(player);
            return;
        }
        MarketItem item = detail == null ? null : Market.get(detail);
        if (item == null) return;
        if (action.buy()) {
            buy(player, item, action.bundles());
        } else {
            sell(player, item, action.bundles());
        }
    }

    private boolean isArrow(int slot) {
        ItemStack item = inventory.getItem(slot);
        return item != null && item.getType() == Material.ARROW;
    }


    /* trades */

    private void buy(Player player, MarketItem item, int bundles) {
        if (!item.buyable()) return;
        double discount = isDailyPrice(item) ? Market.dailyDiscount() : 0;
        int expectedNet = item.buyTotal(bundles, discount);
        busy = true;
        Tasks.async(() -> {
            MarketRepository.Trade trade;
            try {
                trade = MarketRepository.buy(player.getUniqueId(), item.material(), bundles, discount, expectedNet);
            } catch (RuntimeException e) {
                unavailable(player, e, null, 0);
                return;
            }
            Market.update(trade.item());
            int money = PlayerRepository.getMoney(player);
            Runnable onMain = () -> {
                busy = false;
                balance = money;
                switch (trade.outcome()) {
                    case OK -> {
                        give(player, item.material(), bundles * item.amount());
                        player.sendMessage(receipt("§fGekauft: §e" + bundles * item.amount() + "× ", item,
                                " §ffür " + Main.getCurrencyName(trade.net() + trade.tax()) + " §8(davon " + trade.tax() + " Steuer)"));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                        if (isSpawnEgg(item.material())) spawnEggNotice(player);
                    }
                    case INSUFFICIENT_FUNDS -> fail(player, "§fDafür fehlen dir Schilling §8(benötigt: " + (trade.net() + trade.tax()) + ")§f.");
                    case PRICE_CHANGED -> fail(player, "§fDer Preis hat sich gerade geändert, bitte prüfe den neuen Preis.");
                    case UNAVAILABLE -> fail(player, "§fDieses Item gibt es gerade nicht zu kaufen.");
                }
                refreshAll();
            };
            if (!MainThread.run(onMain) && trade.outcome() == MarketRepository.Outcome.OK) {
                // server stop between booking and delivery
                PlayerRepository.addMoney(player.getUniqueId(), trade.net() + trade.tax());
            }
        });
    }

    private void sell(Player player, MarketItem item, int bundles) {
        if (!item.sellable()) return;
        int quantity = bundles * item.amount();
        if (countPlain(player, item.material()) < quantity) {
            render();
            return;
        }
        int expected = item.sellTotal(bundles);
        takePlain(player, item.material(), quantity);
        busy = true;
        Tasks.async(() -> {
            MarketRepository.Trade trade;
            try {
                trade = MarketRepository.sell(player.getUniqueId(), item.material(), bundles, expected);
            } catch (RuntimeException e) {
                // the items were already taken: they go back to the player
                unavailable(player, e, item.material(), quantity);
                return;
            }
            Market.update(trade.item());
            int money = PlayerRepository.getMoney(player);
            MainThread.run(() -> {
                busy = false;
                balance = money;
                if (trade.outcome() == MarketRepository.Outcome.OK) {
                    player.sendMessage(receipt("§fVerkauft: §e" + quantity + "× ", item, " §ffür " + Main.getCurrencyName(trade.net())));
                    player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f, 2f);
                } else {
                    give(player, item.material(), quantity);
                    fail(player, trade.outcome() == MarketRepository.Outcome.PRICE_CHANGED
                            ? "§fDer Ankaufpreis hat sich gerade geändert, bitte prüfe den neuen Preis."
                            : "§fDieses Item wird gerade nicht angekauft.");
                }
                refreshAll();
            });
        });
    }

    private void buyRandom(Player player) {
        MarketItem item = Market.randomDailyOffer();
        int net = Market.randomItemPrice();
        if (item == null || net <= 0) return;
        busy = true;
        Tasks.async(() -> {
            MarketRepository.Trade trade;
            try {
                trade = MarketRepository.buyFixed(player.getUniqueId(), item.material(), item.amount(), net, TaxClass.STANDARD);
            } catch (RuntimeException e) {
                unavailable(player, e, null, 0);
                return;
            }
            int money = PlayerRepository.getMoney(player);
            Runnable onMain = () -> {
                busy = false;
                balance = money;
                if (trade.outcome() == MarketRepository.Outcome.OK) {
                    give(player, item.material(), item.amount());
                    player.sendMessage(receipt("§5Zufall: §e" + item.amount() + "× ", item,
                            " §ffür " + Main.getCurrencyName(trade.net() + trade.tax()) + " §8(davon " + trade.tax() + " Steuer)"));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                    if (isSpawnEgg(item.material())) spawnEggNotice(player);
                } else {
                    fail(player, "§fDafür fehlen dir Schilling §8(benötigt: " + (trade.net() + trade.tax()) + ")§f.");
                }
                render();
            };
            if (!MainThread.run(onMain) && trade.outcome() == MarketRepository.Outcome.OK) {
                PlayerRepository.addMoney(player.getUniqueId(), trade.net() + trade.tax());
            }
        });
    }


    /* helpers */

    // worker thread: a booking failed with a database error, nothing was booked
    private void unavailable(Player player, RuntimeException error, @Nullable Material giveBack, int quantity) {
        Main.getInstance().getSLF4JLogger().warn("Shop trade of {} failed: {}", player.getName(), error.toString());
        MainThread.run(() -> {
            busy = false;
            if (giveBack != null) give(player, giveBack, quantity);
            fail(player, "§fDer Händler ist gerade nicht erreichbar, bitte versuche es gleich nochmal.");
        });
    }

    private static boolean isSpawnEgg(Material material) {
        return material.name().endsWith("_SPAWN_EGG");
    }

    private static void spawnEggNotice(Player player) {
        player.sendMessage(Main.getChatPrefix() + "§c⚠ Hinweis: §fSpawn-Eier können §ckeine Spawner §fmehr umstellen. "
                + "§7Du kannst damit nur Tiere spawnen.");
    }

    private static ItemStack displayStack(MarketItem item) {
        ItemStack stack = new ItemStack(item.material(), Math.min(item.amount(), item.material().getMaxStackSize()));
        if (item.displayName() != null) {
            ItemMeta meta = stack.getItemMeta();
            meta.customName(Text.of(item.displayName()));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // only plain items count (no names, enchantments or damage), so cash notes and named items are never sold
    private static int countPlain(Player player, Material material) {
        ItemStack plain = new ItemStack(material);
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(plain)) count += stack.getAmount();
        }
        return count;
    }

    // how many plain items of this material still fit into the storage slots
    private static int freeSpace(Player player, Material material) {
        ItemStack plain = new ItemStack(material);
        int maxStack = material.getMaxStackSize();
        int space = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
                space += maxStack;
            } else if (stack.isSimilar(plain)) {
                space += Math.max(0, maxStack - stack.getAmount());
            }
        }
        return space;
    }

    private static void takePlain(Player player, Material material, int quantity) {
        ItemStack plain = new ItemStack(material);
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && quantity > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !stack.isSimilar(plain)) continue;
            int taken = Math.min(quantity, stack.getAmount());
            stack.setAmount(stack.getAmount() - taken);
            quantity -= taken;
        }
        player.getInventory().setStorageContents(contents);
    }

    private static void give(Player player, Material material, int quantity) {
        int maxStack = material.getMaxStackSize();
        while (quantity > 0) {
            int amount = Math.min(quantity, maxStack);
            player.getInventory().addItem(new ItemStack(material, amount)).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            quantity -= amount;
        }
    }

    private static Component name(MarketItem item) {
        if (item.displayName() != null) {
            Component name = Text.of(item.displayName());
            if (name != null) return name;
        }
        return Component.translatable(item.material().translationKey(), NamedTextColor.YELLOW);
    }

    private static Component receipt(String before, MarketItem item, String after) {
        return Text.section(Main.getChatPrefix() + before).append(name(item)).append(Text.section(after));
    }

    private static void fail(Player player, String message) {
        player.sendMessage(Main.getChatPrefix() + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
    }

    private static ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.customName(Text.of(name));
        if (!lore.isEmpty()) meta.lore(Text.lore(lore));
        item.setItemMeta(meta);
        return item;
    }

    static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }


}
