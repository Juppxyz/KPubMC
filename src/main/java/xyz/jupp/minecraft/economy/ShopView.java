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
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shop GUI of the "Händler": tabs for the daily offers, every category and an info page.
 * Items are identified by their slot (not by names); all state lives in this holder and is only used on the main thread.
 */
public final class ShopView implements InventoryHolder {

    public enum Tab {
        DAILY("§6Tagesangebote", Material.SUNFLOWER, null),
        FARMING(Category.FARMING),
        BLOCKS(Category.BLOCKS),
        ORES(Category.ORES),
        FOOD(Category.FOOD),
        RARE(Category.RARE),
        MISC(Category.MISC),
        INFO("§fInfo & Steuern", Material.BOOK, null);

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

    private static final int SIZE = 54;
    private static final int INFO_TAB_SLOT = 8;
    private static final int CONTENT_START = 9;
    private static final int CONTENT_SIZE = 36;
    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_RANDOM = 47;
    private static final int SLOT_BALANCE = 49;
    private static final int SLOT_TREASURY = 51;
    private static final int SLOT_NEXT = 53;

    private final UUID viewer;
    private final Inventory inventory;
    private final Map<Integer, Material> contentSlots = new HashMap<>();
    private Tab tab;
    private int page;
    private int balance;
    private boolean busy;

    private ShopView(UUID viewer, Tab tab, int balance) {
        this.viewer = viewer;
        this.tab = tab;
        this.balance = balance;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.section(Main.getShopVillagerName()));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }


    /* open / refresh (main thread) */

    public static void open(@NotNull Player player) {
        Tasks.supplyAsync(() -> PlayerRepository.getMoney(player), money -> {
            if (!player.isOnline()) return;
            ShopView view = new ShopView(player.getUniqueId(), Tab.DAILY, money);
            view.render();
            player.openInventory(view.inventory);
            player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 2f, 2f);
        });
    }

    /** Re-renders every open shop, e.g. after a trade or a price decay. */
    public static void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ShopView view) view.render();
        }
    }

    private void render() {
        inventory.clear();
        contentSlots.clear();
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, "§7---", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            if (slot < CONTENT_START || slot >= CONTENT_START + CONTENT_SIZE) inventory.setItem(slot, pane);
        }

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length - 1; i++) inventory.setItem(i, tabItem(tabs[i]));
        inventory.setItem(INFO_TAB_SLOT, tabItem(Tab.INFO));

        if (tab == Tab.INFO) {
            renderInfo();
        } else {
            List<MarketItem> offers = tab == Tab.DAILY ? Market.dailyOffers() : Market.category(tab.category);
            int pages = Math.max(1, (offers.size() + CONTENT_SIZE - 1) / CONTENT_SIZE);
            page = Math.min(page, pages - 1);
            int slot = CONTENT_START;
            for (MarketItem item : offers.subList(page * CONTENT_SIZE, Math.min(offers.size(), (page + 1) * CONTENT_SIZE))) {
                if (!item.material().isItem()) continue;
                inventory.setItem(slot, offerItem(item, tab == Tab.DAILY));
                contentSlots.put(slot, item.material());
                slot++;
            }
            if (offers.isEmpty()) {
                inventory.setItem(22, named(Material.BARRIER, "§7Hier gibt es gerade nichts.", List.of()));
            }
            if (page > 0) inventory.setItem(SLOT_PREVIOUS, named(Material.ARROW, "§f◀ Zurück", List.of("§7Seite " + page + "/" + pages)));
            if (page < pages - 1) inventory.setItem(SLOT_NEXT, named(Material.ARROW, "§fWeiter ▶", List.of("§7Seite " + (page + 2) + "/" + pages)));
            if (tab == Tab.DAILY) renderRandomItem();
        }

        inventory.setItem(SLOT_BALANCE, named(Material.GOLD_NUGGET, "§fDein Konto: " + Main.getCurrencyName(balance), List.of()));
        inventory.setItem(SLOT_TREASURY, named(Material.GOLD_BLOCK, "§6Staatskasse: " + Main.getCurrencyName((int) Math.min(Integer.MAX_VALUE, Treasury.balance())),
                List.of("§7Alle Steuern fließen hierhin.", "§7Handelssteuer: §a" + percent(Taxes.tradeRate()), "§8/staatskasse")));
    }

    private ItemStack tabItem(Tab candidate) {
        boolean selected = candidate == tab;
        ItemStack item = named(candidate.icon, (selected ? "§a▶ " : "") + candidate.label,
                List.of(selected ? "§7Ausgewählt" : "§7Klicken zum Öffnen"));
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(selected);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack offerItem(MarketItem item, boolean daily) {
        ItemStack stack = new ItemStack(item.material(), Math.min(item.amount(), item.material().getMaxStackSize()));
        ItemMeta meta = stack.getItemMeta();
        if (item.displayName() != null) meta.customName(Text.of(item.displayName()));

        List<String> lore = new ArrayList<>();
        lore.add("§7Menge: §f" + item.amount() + "×");
        if (item.buyable()) {
            int net = item.buyTotal(1, daily ? Market.dailyDiscount() : 0);
            int tax = Taxes.tradeTaxOn(net);
            lore.add("§fKaufen: " + Main.getCurrencyName(net + tax) + " §8(" + net + " + " + tax + " Steuer)");
        }
        if (item.sellable()) lore.add("§fVerkaufen: " + Main.getCurrencyName(item.sellPrice()));
        lore.add(trendLine(item));
        if (daily && item.buyable()) lore.add("§6Tagesangebot: §a-" + percent(Market.dailyDiscount()));
        if (item.description() != null && !item.description().isBlank()) lore.add("§7§o" + item.description());
        lore.add("");
        if (item.buyable()) lore.add("§8Links: kaufen · Shift: " + bundlesPerStack(item) + "× kaufen");
        if (item.sellable()) lore.add("§8Rechts: verkaufen · Shift: alles verkaufen");
        meta.lore(Text.lore(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    private static String trendLine(MarketItem item) {
        double trend = item.trend();
        String base = " §8(Basis " + item.basePrice() + ")";
        if (Math.abs(trend) < 0.02) return "§7Trend: §f● stabil" + base;
        String change = Math.round(Math.abs(trend) * 100) + "%";
        return (trend > 0 ? "§7Trend: §c▲ +" + change : "§7Trend: §a▼ -" + change) + base;
    }

    private void renderRandomItem() {
        int net = Market.randomItemPrice();
        if (net <= 0) return;
        int tax = Taxes.tradeTaxOn(net);
        inventory.setItem(SLOT_RANDOM, named(Material.EXPERIENCE_BOTTLE, "§5§oZufall",
                List.of("§7Ein zufälliges Tagesangebot.", "§fPreis: " + Main.getCurrencyName(net + tax) + " §8(" + net + " + " + tax + " Steuer)")));
    }

    private void renderInfo() {
        ConfigManager config = ConfigManager.getManager();
        inventory.setItem(20, named(Material.WRITABLE_BOOK, "§ePreise", List.of(
                "§7Jeder Kauf erhöht den Preis,",
                "§7jeder Verkauf senkt ihn.",
                "§7Mit der Zeit pendeln die Preise zurück",
                "§7zum Basispreis §8(Halbwertszeit " + formatHours(config.getDemandHalfLifeHours()) + ")§7.",
                "§7Ankaufpreise steigen nie über",
                "§7den Basispreis.")));

        List<String> taxes = new ArrayList<>(List.of(
                "§fHandel: §a" + percent(Taxes.tradeRate()) + " §7(Käufe, Abheben)",
                "§fTod: §a" + percent(Taxes.deathRate()) + " §7(ab 250 Schilling)",
                "§fNether-Transfer §7(gestaffelt):"));
        for (TaxBracket bracket : Taxes.netherBrackets()) {
            taxes.add("§7  ab " + bracket.from() + ": §a" + percent(bracket.rate()));
        }
        taxes.add("§8Alle Steuern gehen an die Staatskasse.");
        inventory.setItem(22, named(Material.PAPER, "§cSteuern", taxes));

        inventory.setItem(24, named(Material.CLOCK, "§6Tagesangebote", List.of(
                "§7Neue Angebote jeden Tag um 0 Uhr.",
                "§7Rabatt: §a-" + percent(Market.dailyDiscount()),
                "§7Heute: §f" + Market.dailyOffers().size() + " Angebote")));
    }


    /* clicks (main thread) */

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || busy) return;
        int slot = event.getRawSlot();

        if (slot >= 0 && slot < Tab.values().length - 1) {
            switchTab(player, Tab.values()[slot]);
            return;
        }
        if (slot == INFO_TAB_SLOT) {
            switchTab(player, Tab.INFO);
            return;
        }
        if (slot == SLOT_PREVIOUS && page > 0) {
            page--;
            render();
            return;
        }
        if (slot == SLOT_NEXT && inventory.getItem(SLOT_NEXT) != null && inventory.getItem(SLOT_NEXT).getType() == Material.ARROW) {
            page++;
            render();
            return;
        }
        if (slot == SLOT_RANDOM && tab == Tab.DAILY) {
            buyRandom(player);
            return;
        }

        Material material = contentSlots.get(slot);
        MarketItem item = material == null ? null : Market.get(material);
        if (item == null) return;
        ClickType click = event.getClick();
        if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
            buy(player, item, click == ClickType.SHIFT_LEFT ? bundlesPerStack(item) : 1);
        } else if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            sell(player, item, click == ClickType.SHIFT_RIGHT);
        }
    }

    private void switchTab(Player player, Tab newTab) {
        if (newTab == tab) return;
        tab = newTab;
        page = 0;
        render();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
    }

    private void buy(Player player, MarketItem item, int bundles) {
        if (!item.buyable()) return;
        boolean daily = tab == Tab.DAILY;
        double discount = daily ? Market.dailyDiscount() : 0;
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
                                " §ffür " + Main.getCurrencyName(trade.net() + trade.tax()) + " §8(" + trade.net() + " + " + trade.tax() + " Steuer → Staatskasse)"));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
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

    private void sell(Player player, MarketItem item, boolean all) {
        if (!item.sellable()) return;
        int owned = countPlain(player, item.material());
        int bundles = all ? owned / item.amount() : (owned >= item.amount() ? 1 : 0);
        if (bundles == 0) {
            fail(player, "§fDu brauchst mindestens §e" + item.amount() + "× §fdavon zum Verkaufen.");
            return;
        }
        int quantity = bundles * item.amount();
        Integer expected = all ? null : item.sellTotal(1);
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
                trade = MarketRepository.buyFixed(player.getUniqueId(), item.material(), item.amount(), net);
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
                            " §ffür " + Main.getCurrencyName(trade.net() + trade.tax()) + " §8(" + trade.net() + " + " + trade.tax() + " Steuer → Staatskasse)"));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
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

    private static int bundlesPerStack(MarketItem item) {
        return Math.max(1, item.material().getMaxStackSize() / item.amount());
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

    private static Component receipt(String before, MarketItem item, String after) {
        Component name = item.displayName() != null
                ? Text.of(item.displayName())
                : Component.translatable(item.material().translationKey(), NamedTextColor.YELLOW);
        return Text.section(Main.getChatPrefix() + before).append(name == null ? Component.empty() : name).append(Text.section(after));
    }

    private static void fail(Player player, String message) {
        player.sendMessage(Main.getChatPrefix() + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
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

    private static String formatHours(double hours) {
        return (hours == Math.rint(hours) ? String.valueOf((long) hours) : String.valueOf(hours)) + " h";
    }

}
