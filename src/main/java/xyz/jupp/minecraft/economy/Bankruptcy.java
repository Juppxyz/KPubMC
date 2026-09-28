package xyz.jupp.minecraft.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.utils.EndAccess;
import xyz.jupp.minecraft.utils.Text;

import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The emergency when the state is broke. The free treasury (balance minus the fixed deposits it owes) is compared with
 * its target (the config's target per active player): below 5 % the state is broke, from 15 % on it has recovered, so it
 * does not flip back and forth. While broke: taxes at the highest factor, more and cheaper daily offers, the state's
 * emergency sale (4 items a day from the catalog items marked for it, 25 % cheaper but never below what the shop pays
 * back, one per player and item a day, the whole price into the treasury) and state bonds at Basil ({@link Bonds}).
 */
public final class Bankruptcy {

    private Bankruptcy() {}

    static final double BROKE_SHARE = 0.05;
    static final double RECOVERED_SHARE = 0.15;
    public static final double EXTRA_DISCOUNT = 0.10;
    public static final int EXTRA_OFFERS = 2;
    public static final double SALE_DISCOUNT = 0.25;
    static final int SALE_ITEMS = 4;
    private static final int ACTIVE_DAYS = 7;
    private static final String STATE_KEY = "state_broke";
    // a young state that never reached the recovery line yet cannot go broke (it simply has not taken much in so far)
    private static final String FOUNDED_KEY = "state_founded";
    private static final String DEFAULTS_KEY = "emergency_sale_defaults";
    // what the state may sell in an emergency until an admin changes it (/shopadmin set <item> notverkauf ja/nein)
    private static final List<String> DEFAULT_SALE = List.of("DIAMOND", "GOLD_INGOT", "EMERALD", "EXPERIENCE_BOTTLE", "ENDER_PEARL",
            "GOLDEN_APPLE", "TOTEM_OF_UNDYING", "NAME_TAG", "SADDLE", "HEART_OF_THE_SEA", "ECHO_SHARD", "BREEZE_ROD",
            "NAUTILUS_SHELL", "BLAZE_ROD", "OBSIDIAN");

    private static volatile boolean broke;
    private static volatile boolean founded;
    private static volatile List<Material> sale = List.of();
    private static volatile @Nullable LocalDate saleDay;

    public static boolean isBroke() {
        return broke;
    }

    /** Today's emergency sale, empty while the state is not broke. */
    public static List<Material> sale() {
        return broke ? sale : List.of();
    }


    /* state */

    public record Level(long free, long brokeBelow, long recoveredAt) {}

    static Level level(Connection connection) throws SQLException {
        Level level = Database.queryOne(connection, """
                SELECT (SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger)
                           - (SELECT COALESCE(SUM(amount), 0) FROM bank_deposits WHERE NOT paid_out AND in_treasury),
                       (SELECT COUNT(*) FROM players WHERE last_seen >= now() - make_interval(days => ?))""",
                row -> {
                    long target = (long) ConfigManager.getManager().getTreasuryTargetPerPlayer() * Math.max(1, row.getLong(2));
                    return new Level(row.getLong(1), Math.round(target * BROKE_SHARE), Math.round(target * RECOVERED_SHARE));
                }, ACTIVE_DAYS);
        return Objects.requireNonNull(level);
    }

    public static Level level() {
        return Database.withConnection(Bankruptcy::level);
    }

    /** Blocking, in onEnable after the market: the state from before the restart, the default sale items once. */
    public static void load() {
        broke = "true".equals(meta(STATE_KEY));
        founded = meta(FOUNDED_KEY) != null;
        if (meta(DEFAULTS_KEY) == null) {
            String placeholders = String.join(", ", Collections.nCopies(DEFAULT_SALE.size(), "?"));
            Database.update("UPDATE market_items SET emergency_sale = TRUE WHERE material IN (" + placeholders + ")", DEFAULT_SALE.toArray());
            setMeta(DEFAULTS_KEY, "1");
        }
        if (broke) ensureSale();
    }

    /** Worker, every few minutes: switches the state when the treasury crossed a line; the announcement or null. */
    public static @Nullable Component check() {
        Level level = level();
        if (!founded) {
            if (level.free() < level.recoveredAt()) return null;
            founded = true;
            setMeta(FOUNDED_KEY, "1");
        }
        boolean now = broke ? level.free() < level.recoveredAt() : level.free() < level.brokeBelow();
        if (now == broke) {
            if (broke) ensureSale();
            return null;
        }
        broke = now;
        setMeta(STATE_KEY, String.valueOf(now));
        Main.getInstance().getSLF4JLogger().info("state {}: free treasury {} (broke below {}, recovered at {})",
                now ? "BROKE" : "recovered", level.free(), level.brokeBelow(), level.recoveredAt());
        if (now) {
            ensureSale();
            return Text.section(Main.getChatPrefix() + "§4§lDer Staat ist pleite! §cDie Steuern steigen. "
                    + "§7Notverkauf im Shop, Staatsanleihen bei Basil, mehr Rabatt beim Händler.");
        }
        return Text.section(Main.getChatPrefix() + "§a§lDer Staat hat sich erholt! §7Die Steuern sinken wieder.");
    }

    // today's items: drawn once per day while broke, stored so a restart keeps them
    private static void ensureSale() {
        LocalDate today = LocalDate.now(Market.ZONE);
        if (today.equals(saleDay)) return;
        List<Material> stored = Database.query("SELECT material FROM emergency_sale WHERE day = ? ORDER BY material",
                row -> Material.matchMaterial(row.getString(1)), Date.valueOf(today)).stream().filter(Objects::nonNull).toList();
        if (stored.isEmpty()) {
            List<Material> pool = new ArrayList<>(Database.query("SELECT material FROM market_items WHERE emergency_sale AND enabled",
                    row -> Material.matchMaterial(row.getString(1))).stream()
                    .filter(Objects::nonNull).filter(EndAccess::isAvailable).filter(material -> Market.get(material) != null).toList());
            Collections.shuffle(pool);
            stored = List.copyOf(pool.subList(0, Math.min(SALE_ITEMS, pool.size())));
            for (Material material : stored) {
                Database.update("INSERT INTO emergency_sale (day, material) VALUES (?, ?) ON CONFLICT DO NOTHING", Date.valueOf(today), material.name());
            }
        }
        sale = stored;
        saleDay = today;
    }


    /* the emergency sale */

    public enum Outcome { OK, ALREADY_BOUGHT, INSUFFICIENT_FUNDS, PRICE_CHANGED, UNAVAILABLE }

    /** Price of one bundle in the emergency sale: 25 % off, never below what the shop could ever pay back for it. */
    public static int salePrice(@NotNull MarketItem item) {
        int discounted = (int) Math.ceil(item.buyPrice() * (1.0 - SALE_DISCOUNT));
        return Math.max(Math.max(1, discounted), Hondo.floor(item, item.amount()));
    }

    /** What the player bought in today's sale (worker). */
    public static List<Material> boughtToday(@NotNull UUID player) {
        return Database.query("SELECT material FROM emergency_purchases WHERE day = ? AND player_uuid = ?",
                row -> Material.matchMaterial(row.getString(1)), Date.valueOf(LocalDate.now(Market.ZONE)), player)
                .stream().filter(Objects::nonNull).toList();
    }

    /** Buys one bundle from today's sale; the whole price goes into the treasury. */
    public static Outcome buy(@NotNull UUID player, @NotNull Material material, int expectedPrice) {
        LocalDate today = LocalDate.now(Market.ZONE);
        if (!broke || !sale.contains(material)) return Outcome.UNAVAILABLE;
        int[] price = {0};
        Outcome outcome = Database.inTransaction(connection -> {
            MarketItem item = MarketRepository.lock(connection, material);
            if (item == null || !item.enabled()) return Outcome.UNAVAILABLE;
            price[0] = salePrice(item);
            if (price[0] != expectedPrice) return Outcome.PRICE_CHANGED;
            // the account row is locked first, so the money checked here is still there when it is taken
            Long money = Database.queryOne(connection, "SELECT money FROM players WHERE uuid = ? FOR UPDATE", row -> row.getLong(1), player);
            if (money == null || money < price[0]) return Outcome.INSUFFICIENT_FUNDS;
            // the primary key allows one bundle per player, item and day
            if (Database.update(connection, "INSERT INTO emergency_purchases (day, player_uuid, material) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    Date.valueOf(today), player, material.name()) == 0) return Outcome.ALREADY_BOUGHT;
            Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ?", price[0], player);
            Treasury.deposit(connection, Treasury.Source.EMERGENCY_SALE, price[0], player);
            MarketRepository.logTrade(connection, player, material, "BUY", item.amount(), price[0], 0);
            return Outcome.OK;
        });
        if (outcome == Outcome.OK) Treasury.committed(price[0]);
        return outcome;
    }


    /* meta */

    private static @Nullable String meta(String key) {
        return Database.queryOne("SELECT value FROM market_meta WHERE key = ?", row -> row.getString(1), key);
    }

    private static void setMeta(String key, String value) {
        Database.update("INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", key, value);
    }

}
