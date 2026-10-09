package xyz.jupp.minecraft.economy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static xyz.jupp.minecraft.economy.ShopView.countPlain;
import static xyz.jupp.minecraft.economy.ShopView.fail;
import static xyz.jupp.minecraft.economy.ShopView.freeSpace;
import static xyz.jupp.minecraft.economy.ShopView.give;
import static xyz.jupp.minecraft.economy.ShopView.named;
import static xyz.jupp.minecraft.economy.ShopView.pane;
import static xyz.jupp.minecraft.economy.ShopView.takePlain;

/**
 * Hondo's counter: his goods, the exchanges and the friendship offers (rules and prices in {@link Hondo}).
 * Items the player gives are taken on the main thread before the booking and given back if it fails.
 */
public final class HondoView implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int SLOT_BALANCE = 0;
    private static final int SLOT_FRIENDSHIP = 4;
    private static final int SLOT_CLOSE = 8;
    private static final int SLOT_GOODS_LABEL = 9;
    private static final int GOODS_START = 10;
    private static final int SLOT_EXCHANGE_LABEL = 27;
    private static final int EXCHANGE_START = 28;
    private static final int SLOT_OFFER_LABEL = 45;
    private static final int OFFER_START = 47;
    // clicks right after a re-render are ignored (double clicks, scroll mods)
    private static final long CLICK_COOLDOWN_MILLIS = 300;

    private final Player viewer;
    private final Inventory inventory;
    private HondoRepository.Friendship friendship;
    private int balance;
    private boolean busy;
    private long ignoreClicksUntil;

    private HondoView(Player viewer, HondoRepository.Friendship friendship, int balance) {
        this.viewer = viewer;
        this.friendship = friendship;
        this.balance = balance;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.section(Main.getJewelerVillagerName() + " §8» §7Juwelier"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private record State(HondoRepository.Friendship friendship, int balance) {}


    /* open / refresh (main thread) */

    public static void open(@NotNull Player player) {
        Tasks.supplyAsync(() -> new State(HondoRepository.friendship(player.getUniqueId()), PlayerRepository.getMoney(player)), state -> {
            if (!player.isOnline()) return;
            HondoView view = new HondoView(player, state.friendship(), state.balance());
            view.render();
            player.openInventory(view.inventory);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        });
    }

    /** Re-renders every open counter, e.g. after the prices changed. */
    public static void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof HondoView view) view.render();
        }
    }

    private void render() {
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);

        int level = friendship.level();
        inventory.setItem(SLOT_BALANCE, named(Material.GOLD_NUGGET, "§fDein Konto: " + Main.getCurrencyName(balance), List.of()));
        inventory.setItem(SLOT_FRIENDSHIP, friendshipItem(level));
        inventory.setItem(SLOT_CLOSE, named(Material.BARRIER, "§cSchließen", List.of()));

        inventory.setItem(SLOT_GOODS_LABEL, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Hondos Ware §7▶",
                List.of("§7Preise wie im Shop.", "§7Als Freund bekommst du Rabatt.")));
        for (int i = 0; i < Hondo.GOODS.size(); i++) inventory.setItem(GOODS_START + i, goodItem(Hondo.GOODS.get(i), level));

        inventory.setItem(SLOT_EXCHANGE_LABEL, named(Material.LIME_STAINED_GLASS_PANE, "§aTauschen §7▶",
                List.of("§7Hondo nimmt nur, was auch", "§7der Shop ankauft. Steuerfrei.")));
        for (int i = 0; i < Hondo.EXCHANGES.size(); i++) inventory.setItem(EXCHANGE_START + i, exchangeItem(Hondo.EXCHANGES.get(i), level));

        inventory.setItem(SLOT_OFFER_LABEL, named(Material.PINK_STAINED_GLASS_PANE, "§dFreundschaftsangebote §7▶",
                List.of("§7Jede Stufe schaltet ein", "§7Angebot frei, jedes nur einmal.")));
        for (int i = 0; i < Hondo.OFFERS.size(); i++) inventory.setItem(OFFER_START + i, offerItem(Hondo.OFFERS.get(i), level));
    }

    private ItemStack friendshipItem(int level) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Stufe §f" + level + "§7/" + Hondo.MAX_LEVEL);
        if (level < Hondo.MAX_LEVEL) {
            double next = Hondo.pointsFor(level + 1);
            lore.add(progressBar(friendship.points(), Hondo.pointsFor(level), next)
                    + " §7" + (int) friendship.points() + "/" + (int) next + " bis " + Hondo.levelName(level + 1));
            if (friendship.nextLevelTomorrow()) lore.add("§8Heute schon aufgestiegen, nächste Stufe ab morgen.");
        } else {
            lore.add("§6Höchste Stufe erreicht!");
        }
        lore.add("");
        if (level > 0) {
            lore.add("§7Rabatt auf Hondos Ware: §a-" + ShopView.percent(Hondo.discount(level)));
        }
        lore.add("§7Hondos Anteil beim Tausch: §f" + ShopView.percent(Hondo.fee(level)));
        lore.add("");
        if (friendship.activeToday()) {
            lore.add("§aHondo hat sich heute über dich gefreut.");
        } else {
            lore.add("§7Heute: §f" + friendship.tradesToday() + "/" + Hondo.ACTIVE_TRADES + " Handel §7oder §f"
                    + friendship.volumeToday() + "/" + Hondo.ACTIVE_VOLUME + " Schilling");
            if (friendship.points() > 0) lore.add("§8Sonst sinkt eure Freundschaft über Nacht.");
        }
        lore.add("");
        lore.add("§8Bisher: " + friendship.trades() + " Handel, " + friendship.volume() + " Schilling");
        return named(Material.NAME_TAG, "§dFreundschaft: §f" + Hondo.levelName(level), lore);
    }

    private static String progressBar(double points, double from, double to) {
        int filled = (int) Math.max(0, Math.min(10, Math.floor((points - from) / (to - from) * 10)));
        return "§a" + "|".repeat(filled) + "§8" + "|".repeat(10 - filled);
    }

    private ItemStack goodItem(Material material, int level) {
        MarketItem item = Market.get(material);
        if (item == null || !item.enabled()) return named(Material.GRAY_DYE, "§7Gerade nicht vorrätig", List.of());
        double discount = Hondo.discount(level);
        int one = Hondo.salePrice(item, 1, discount);
        int bulk = Hondo.salePrice(item, Hondo.BULK, discount);
        List<String> lore = new ArrayList<>();
        lore.add("§7Preis: §f" + one + " Schilling §7pro Stück");
        if (discount > 0) lore.add("§7Freundschaftsrabatt: §a-" + ShopView.percent(discount));
        lore.add("§7zzgl. " + ShopView.percent(Taxes.rate(Hondo.TAX_CLASS)) + " Steuer");
        lore.add("");
        lore.add("§e» Linksklick: §f1 Stück §8(" + gross(one) + " inkl. Steuer)");
        lore.add("§e» Rechtsklick: §f" + Hondo.BULK + " Stück §8(" + gross(bulk) + " inkl. Steuer)");
        return named(material, Hondo.label(material), lore);
    }

    private ItemStack exchangeItem(Hondo.Exchange exchange, int level) {
        MarketItem give = Market.get(exchange.give());
        MarketItem get = Market.get(exchange.get());
        int input = give == null || get == null ? 0 : Hondo.exchangeInput(give, get, exchange.amount(), Hondo.fee(level));
        if (input == 0) {
            return named(Material.GRAY_DYE, "§7" + Text.strip(Hondo.label(exchange.give())) + " → " + Text.strip(Hondo.label(exchange.get())),
                    List.of("§7Dieser Tausch geht gerade nicht."));
        }
        List<String> lore = new ArrayList<>();
        lore.add("§7Du gibst: §f" + input + "× " + Hondo.label(exchange.give()));
        lore.add("§7Du bekommst: §f" + exchange.amount() + "× " + Hondo.label(exchange.get()));
        lore.add("§7Du hast: §f" + countPlain(viewer, exchange.give()));
        lore.add("§8Der Kurs folgt dem Shop, Hondos Anteil: " + ShopView.percent(Hondo.fee(level)));
        lore.add("");
        lore.add("§e» Linksklick: tauschen");
        ItemStack stack = named(exchange.get(), "§f" + input + "× " + Hondo.label(exchange.give()) + " §7→ §f"
                + exchange.amount() + "× " + Hondo.label(exchange.get()), lore);
        stack.setAmount(Math.min(exchange.amount(), exchange.get().getMaxStackSize()));
        return stack;
    }

    private ItemStack offerItem(Hondo.FriendOffer offer, int level) {
        MarketItem item = Market.get(offer.material());
        String what = offer.amount() + "× " + Hondo.label(offer.material());
        if (friendship.claimed().contains(offer.level())) {
            return named(Material.LIGHT_GRAY_DYE, "§8Stufe " + offer.level() + ": eingelöst", List.of("§8" + Text.strip(what)));
        }
        if (item == null || !item.enabled()) return named(Material.GRAY_DYE, "§7Gerade nicht vorrätig", List.of());
        int net = Hondo.offerPrice(item, offer);
        List<String> lore = new ArrayList<>();
        lore.add("§7Freundschaftspreis: §f" + net + " Schilling §8(-" + ShopView.percent(offer.discount()) + ")");
        lore.add("§aSteuerfrei §8(Hondos Freundschaftsgeschenk)");
        lore.add("§7Nur einmal zu haben!");
        lore.add("");
        if (level >= offer.level()) {
            lore.add("§e» Linksklick: kaufen");
            return named(offer.material(), "§dStufe " + offer.level() + ": §f" + what, lore);
        }
        lore.add("§cAb Stufe " + offer.level() + " (" + Hondo.levelName(offer.level()) + ")");
        return named(offer.material(), "§7Stufe " + offer.level() + ": §f" + what, lore);
    }

    private static int gross(int net) {
        return net + Taxes.taxOn(net, Hondo.TAX_CLASS);
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

        if (slot == SLOT_CLOSE) {
            Tasks.sync(player::closeInventory);
        } else if (slot >= GOODS_START && slot < GOODS_START + Hondo.GOODS.size()) {
            buy(player, Hondo.GOODS.get(slot - GOODS_START), click == ClickType.RIGHT ? Hondo.BULK : 1);
        } else if (slot >= EXCHANGE_START && slot < EXCHANGE_START + Hondo.EXCHANGES.size() && click == ClickType.LEFT) {
            exchange(player, slot - EXCHANGE_START);
        } else if (slot >= OFFER_START && slot < OFFER_START + Hondo.OFFERS.size() && click == ClickType.LEFT) {
            claim(player, Hondo.OFFERS.get(slot - OFFER_START));
        }
    }

    private void buy(Player player, Material material, int units) {
        MarketItem item = Market.get(material);
        if (item == null || !item.enabled()) return;
        if (freeSpace(player, material) < units) {
            fail(player, "§fDafür ist in deinem Inventar nicht genug Platz.");
            return;
        }
        UUID uuid = player.getUniqueId();
        int expected = Hondo.salePrice(item, units, Hondo.discount(friendship.level()));
        busy = true;
        Tasks.async(() -> {
            HondoRepository.Result result;
            try {
                result = HondoRepository.buy(uuid, material, units, expected);
            } catch (RuntimeException e) {
                unavailable(player, e, null, 0);
                return;
            }
            boolean ok = result.outcome() == HondoRepository.Outcome.OK;
            String limit = result.outcome() == HondoRepository.Outcome.LIMIT ? ShopView.limitMessage(uuid, item) : null;
            Integer money = moneyOrNull(uuid);
            HondoRepository.Friendship fresh = ok ? result.friendship() : friendshipOrNull(uuid);
            Runnable onMain = () -> {
                busy = false;
                balance = money != null ? money : balance - (ok ? result.net() + result.tax() : 0);
                if (fresh != null) friendship = fresh;
                switch (result.outcome()) {
                    case OK -> {
                        deliver(uuid, material, units);
                        player.sendMessage(Main.getChatPrefix() + "§fGekauft: §e" + units + "× " + Hondo.label(material) + " §ffür "
                                + Main.getCurrencyName(result.net() + result.tax()) + " §8(davon " + result.tax() + " Steuer)");
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 2f);
                        traded(player, result);
                    }
                    case INSUFFICIENT_FUNDS -> fail(player, "§fDafür fehlen dir Schilling §8(benötigt: " + (result.net() + result.tax()) + ")§f.");
                    case LIMIT -> fail(player, limit);
                    case PRICE_CHANGED -> fail(player, "§fDer Preis hat sich gerade geändert, bitte prüfe den neuen Preis.");
                    default -> fail(player, "§fDas hat Hondo gerade nicht.");
                }
                ShopView.refreshAll();
            };
            if (!MainThread.run(onMain) && ok) keepForLater(uuid, material, units);
        });
    }

    private void exchange(Player player, int index) {
        Hondo.Exchange exchange = Hondo.EXCHANGES.get(index);
        MarketItem give = Market.get(exchange.give());
        MarketItem get = Market.get(exchange.get());
        if (give == null || get == null) return;
        int input = Hondo.exchangeInput(give, get, exchange.amount(), Hondo.fee(friendship.level()));
        if (input == 0) return;
        if (countPlain(player, exchange.give()) < input) {
            fail(player, "§fDafür brauchst du §e" + input + "× " + Hondo.label(exchange.give()) + "§f.");
            return;
        }
        if (freeSpace(player, exchange.get()) < exchange.amount()) {
            fail(player, "§fDafür ist in deinem Inventar nicht genug Platz.");
            return;
        }
        UUID uuid = player.getUniqueId();
        // taken right away on the main thread, so the same items cannot be exchanged twice
        takePlain(player, exchange.give(), input);
        busy = true;
        Tasks.async(() -> {
            HondoRepository.Result result;
            try {
                result = HondoRepository.exchange(uuid, index, input);
            } catch (RuntimeException e) {
                unavailable(player, e, exchange.give(), input);
                return;
            }
            boolean ok = result.outcome() == HondoRepository.Outcome.OK;
            HondoRepository.Friendship fresh = ok ? result.friendship() : friendshipOrNull(uuid);
            // what the player gets now: Hondo's goods, or the own items back
            Material receive = ok ? exchange.get() : exchange.give();
            int quantity = ok ? exchange.amount() : input;
            Runnable onMain = () -> {
                busy = false;
                if (fresh != null) friendship = fresh;
                deliver(uuid, receive, quantity);
                if (ok) {
                    player.sendMessage(Main.getChatPrefix() + "§fGetauscht: §e" + input + "× " + Hondo.label(exchange.give())
                            + " §f→ §e" + exchange.amount() + "× " + Hondo.label(exchange.get()));
                    player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
                    traded(player, result);
                } else {
                    fail(player, result.outcome() == HondoRepository.Outcome.PRICE_CHANGED
                            ? "§fDer Kurs hat sich gerade geändert, bitte prüfe den neuen Kurs."
                            : "§fDieser Tausch geht gerade nicht.");
                }
                ShopView.refreshAll();
            };
            if (!MainThread.run(onMain)) keepForLater(uuid, receive, quantity);
        });
    }

    private void claim(Player player, Hondo.FriendOffer offer) {
        if (friendship.claimed().contains(offer.level())) return;
        if (friendship.level() < offer.level()) {
            player.sendMessage(Hondo.PREFIX + "Das hebe ich für gute Freunde auf. Komm wieder, wenn wir uns besser kennen.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        MarketItem item = Market.get(offer.material());
        if (item == null || !item.enabled()) return;
        if (freeSpace(player, offer.material()) < offer.amount()) {
            fail(player, "§fDafür ist in deinem Inventar nicht genug Platz.");
            return;
        }
        UUID uuid = player.getUniqueId();
        int expected = Hondo.offerPrice(item, offer);
        busy = true;
        Tasks.async(() -> {
            HondoRepository.Result result;
            try {
                result = HondoRepository.claim(uuid, offer.level(), expected);
            } catch (RuntimeException e) {
                unavailable(player, e, null, 0);
                return;
            }
            boolean ok = result.outcome() == HondoRepository.Outcome.OK;
            Integer money = moneyOrNull(uuid);
            HondoRepository.Friendship fresh = ok ? result.friendship() : friendshipOrNull(uuid);
            Runnable onMain = () -> {
                busy = false;
                balance = money != null ? money : balance - (ok ? result.net() + result.tax() : 0);
                if (fresh != null) friendship = fresh;
                switch (result.outcome()) {
                    case OK -> {
                        deliver(uuid, offer.material(), offer.amount());
                        player.sendMessage(Hondo.PREFIX + "Für dich, mein Freund. Erzähl es nicht herum.");
                        player.sendMessage(Main.getChatPrefix() + "§fGekauft: §e" + offer.amount() + "× " + Hondo.label(offer.material())
                                + " §ffür " + Main.getCurrencyName(result.net()) + " §8(steuerfrei)");
                        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
                        traded(player, result);
                    }
                    case INSUFFICIENT_FUNDS -> fail(player, "§fDafür fehlen dir Schilling §8(benötigt: " + result.net() + ")§f.");
                    case PRICE_CHANGED -> fail(player, "§fDer Preis hat sich gerade geändert, bitte prüfe den neuen Preis.");
                    case ALREADY_CLAIMED -> fail(player, "§fDieses Angebot hast du schon eingelöst.");
                    case LEVEL_TOO_LOW -> fail(player, "§fDafür ist eure Freundschaft gerade nicht eng genug.");
                    default -> fail(player, "§fDas hat Hondo gerade nicht.");
                }
                render();
            };
            if (!MainThread.run(onMain) && ok) keepForLater(uuid, offer.material(), offer.amount());
        });
    }

    // main thread, after a booked trade: new friendship, level-up message
    private void traded(Player player, HondoRepository.Result result) {
        HondoRepository.Friendship after = result.friendship();
        if (after == null) return;
        friendship = after;
        int level = after.level();
        if (level > result.levelBefore()) {
            player.sendMessage(Hondo.PREFIX + "Schön, dich so oft zu sehen! Du bist jetzt mein §d" + Hondo.levelName(level) + "§f.");
            Hondo.FriendOffer offer = Hondo.offer(level);
            if (offer != null && !after.claimed().contains(level)) {
                player.sendMessage(Hondo.PREFIX + "Ich habe da etwas für dich zurückgelegt: §e" + offer.amount() + "× "
                        + Hondo.label(offer.material()) + " §fzum Freundschaftspreis.");
            }
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        }
    }

    // worker thread: the booking failed with a database error, nothing was booked
    private void unavailable(Player player, RuntimeException error, @Nullable Material giveBack, int quantity) {
        Main.getInstance().getSLF4JLogger().warn("Hondo trade of {} failed: {}", player.getName(), error.toString());
        UUID uuid = player.getUniqueId();
        boolean scheduled = MainThread.run(() -> {
            busy = false;
            if (giveBack != null) deliver(uuid, giveBack, quantity);
            fail(player, "§fHondo ist gerade nicht erreichbar, bitte versuche es gleich nochmal.");
        });
        if (!scheduled && giveBack != null) keepForLater(uuid, giveBack, quantity);
    }


    /* delivery: a Player object from before a logout is never saved again, so the goods go to the player online now */

    // main thread
    static void deliver(UUID uuid, Material material, int quantity) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            give(online, material, quantity);
        } else {
            try {
                Tasks.async(() -> keepForLater(uuid, material, quantity));
            } catch (IllegalPluginAccessException e) {
                // server stop (MainThread.runPending): the database is still open
                keepForLater(uuid, material, quantity);
            }
        }
    }

    // worker thread: stored until the next join
    static void keepForLater(UUID uuid, Material material, int quantity) {
        try {
            HondoRepository.addPending(uuid, material, quantity);
            Main.getInstance().getSLF4JLogger().info("Hondo keeps {}x {} for {} until the next join", quantity, material, uuid);
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().error("Hondo could not keep {}x {} for {}", quantity, material, uuid, e);
        }
    }

    /** At join: goods from a trade the player left during. */
    public static void deliverPending(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(() -> {
            List<HondoRepository.Pending> pending;
            try {
                pending = HondoRepository.takePending(uuid);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Hondo's pending goods for {} could not be read: {}", uuid, e.toString());
                return;
            }
            if (pending.isEmpty()) return;
            boolean scheduled = MainThread.run(() -> {
                for (HondoRepository.Pending goods : pending) deliver(uuid, goods.material(), goods.quantity());
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) online.sendMessage(Hondo.PREFIX + "Du bist neulich so schnell weg gewesen. Hier, das gehört dir noch.");
            });
            if (!scheduled) pending.forEach(goods -> keepForLater(uuid, goods.material(), goods.quantity()));
        });
    }

    // worker thread, after the booking: a failed read must not stop the delivery
    static @Nullable Integer moneyOrNull(UUID uuid) {
        try {
            return PlayerRepository.getMoney(uuid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static HondoRepository.@Nullable Friendship friendshipOrNull(UUID uuid) {
        try {
            return HondoRepository.friendship(uuid);
        } catch (RuntimeException e) {
            return null;
        }
    }

}
