package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.utils.EndAccess;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tables hondo_friends, hondo_trades, hondo_claims and hondo_pending. Every method is blocking.
 * A trade locks the catalog rows first (sorted by material) and then the player's friendship row, so trades of one
 * player run one after another and the prices are computed from the locked rows.
 */
public final class HondoRepository {

    private HondoRepository() {}

    public enum Outcome { OK, INSUFFICIENT_FUNDS, PRICE_CHANGED, UNAVAILABLE, ALREADY_CLAIMED, LEVEL_TOO_LOW }

    /** The friendship as the GUI shows it; the trades of today decide whether the points shrink over night. */
    public record Friendship(double points, int trades, long volume, int tradesToday, long volumeToday, Set<Integer> claimed) {

        public static final Friendship NONE = new Friendship(0, 0, 0, 0, 0, Set.of());

        public int level() {
            return Hondo.level(points);
        }

        public boolean activeToday() {
            return tradesToday >= Hondo.ACTIVE_TRADES || volumeToday >= Hondo.ACTIVE_VOLUME;
        }
    }

    /** Result of a trade. net/tax: the booked (or current) price, input: the pieces an exchange needs. */
    public record Result(Outcome outcome, int net, int tax, int input, int levelBefore, @Nullable Friendship friendship) {

        static Result of(Outcome outcome) {
            return new Result(outcome, 0, 0, 0, 0, null);
        }
    }

    private record Friend(double points, int trades, long volume) {}

    private static final String EVALUATED_DAY_KEY = "hondo_evaluated_day";


    /* friendship */

    public static Friendship friendship(@NotNull UUID player) {
        return Database.withConnection(connection -> {
            Friend friend = Database.queryOne(connection, "SELECT points, trades, volume FROM hondo_friends WHERE player_uuid = ?",
                    row -> new Friend(row.getDouble(1), row.getInt(2), row.getLong(3)), player);
            return friendship(connection, player, friend == null ? new Friend(0, 0, 0) : friend);
        });
    }

    private static Friendship friendship(Connection connection, UUID player, Friend friend) throws SQLException {
        long[] today = Database.queryOne(connection, "SELECT COUNT(*), COALESCE(SUM(value), 0) FROM hondo_trades WHERE player_uuid = ? AND created_at >= ?",
                row -> new long[]{row.getLong(1), row.getLong(2)}, player, startOfToday());
        Set<Integer> claimed = new HashSet<>(Database.query(connection, "SELECT level FROM hondo_claims WHERE player_uuid = ?",
                row -> row.getInt(1), player));
        return new Friendship(friend.points(), friend.trades(), friend.volume(),
                today == null ? 0 : (int) today[0], today == null ? 0 : today[1], Set.copyOf(claimed));
    }

    private static Friend lockFriend(Connection connection, UUID player) throws SQLException {
        Database.update(connection, "INSERT INTO hondo_friends (player_uuid) VALUES (?) ON CONFLICT DO NOTHING", player);
        return Database.queryOne(connection, "SELECT points, trades, volume FROM hondo_friends WHERE player_uuid = ? FOR UPDATE",
                row -> new Friend(row.getDouble(1), row.getInt(2), row.getLong(3)), player);
    }

    // stores the trade and its points, value is what the player received (in Schilling)
    private static Friendship recordTrade(Connection connection, UUID player, Friend friend, String kind, Material material,
                                          int quantity, @Nullable Material given, int givenQuantity, int value) throws SQLException {
        Integer tradesToday = Database.queryOne(connection, "SELECT COUNT(*) FROM hondo_trades WHERE player_uuid = ? AND created_at >= ?",
                row -> row.getInt(1), player, startOfToday());
        double points = Hondo.pointsForTrade(value, tradesToday == null ? 0 : tradesToday);
        Database.update(connection, "INSERT INTO hondo_trades (player_uuid, kind, material, quantity, given_material, given_quantity, value, points) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                player, kind, material.name(), quantity, given == null ? null : given.name(), givenQuantity, value, points);
        Database.update(connection, "UPDATE hondo_friends SET points = points + ?, trades = trades + 1, volume = volume + ?, last_trade_at = now() "
                + "WHERE player_uuid = ?", points, value, player);
        return friendship(connection, player, new Friend(friend.points() + points, friend.trades() + 1, friend.volume() + value));
    }

    private static boolean withdraw(Connection connection, UUID player, int amount) throws SQLException {
        return Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?", amount, player, amount) > 0;
    }

    private static void setDemand(Connection connection, MarketItem item, double demand) throws SQLException {
        Database.update(connection, "UPDATE market_items SET demand = ? WHERE material = ?", demand, item.material().name());
    }


    /* trades */

    /** Buys pieces of one of Hondo's goods. expectedNet is the price the player saw, a different price books nothing. */
    public static Result buy(@NotNull UUID player, @NotNull Material material, int units, int expectedNet) {
        if (!Hondo.GOODS.contains(material) || units <= 0) return Result.of(Outcome.UNAVAILABLE);
        List<MarketItem> changed = new ArrayList<>();
        Result result = Database.inTransaction(connection -> {
            MarketItem item = MarketRepository.lock(connection, material);
            if (item == null || !item.enabled() || !EndAccess.isAvailable(material)) return Result.of(Outcome.UNAVAILABLE);
            Friend friend = lockFriend(connection, player);
            int level = Hondo.level(friend.points());

            int net = Hondo.salePrice(item, units, Hondo.discount(level));
            int tax = Taxes.taxOn(net, Hondo.TAX_CLASS);
            if (net != expectedNet) return new Result(Outcome.PRICE_CHANGED, net, tax, 0, level, null);
            if (!withdraw(connection, player, net + tax)) return new Result(Outcome.INSUFFICIENT_FUNDS, net, tax, 0, level, null);

            double demand = item.demand() + (double) units / item.amount();
            setDemand(connection, item, demand);
            MarketRepository.logTrade(connection, player, material, "BUY", units, net, tax);
            Treasury.deposit(connection, Treasury.Source.TRADE_TAX, tax, player);
            Friendship after = recordTrade(connection, player, friend, "BUY", material, units, null, 0, net);
            changed.add(item.withDemand(demand));
            return new Result(Outcome.OK, net, tax, 0, level, after);
        });
        if (result.outcome() == Outcome.OK) {
            Treasury.committed(result.tax());
            changed.forEach(Market::update);
        }
        return result;
    }

    /**
     * Exchanges items (the caller has already taken expectedInput pieces of the exchange's give material from the player).
     * If the needed amount changed meanwhile, nothing is booked and the caller gives the items back.
     */
    public static Result exchange(@NotNull UUID player, int index, int expectedInput) {
        if (index < 0 || index >= Hondo.EXCHANGES.size()) return Result.of(Outcome.UNAVAILABLE);
        Hondo.Exchange exchange = Hondo.EXCHANGES.get(index);
        List<MarketItem> changed = new ArrayList<>();
        Result result = Database.inTransaction(connection -> {
            // both rows in material order (like MarketRepository.decayDemand), so nothing waits for each other in a circle
            boolean giveFirst = exchange.give().name().compareTo(exchange.get().name()) < 0;
            MarketItem first = MarketRepository.lock(connection, giveFirst ? exchange.give() : exchange.get());
            MarketItem second = MarketRepository.lock(connection, giveFirst ? exchange.get() : exchange.give());
            MarketItem give = giveFirst ? first : second;
            MarketItem get = giveFirst ? second : first;
            if (give == null || get == null || !EndAccess.isAvailable(get.material())) return Result.of(Outcome.UNAVAILABLE);
            Friend friend = lockFriend(connection, player);
            int level = Hondo.level(friend.points());

            int input = Hondo.exchangeInput(give, get, exchange.amount(), Hondo.fee(level));
            if (input == 0) return Result.of(Outcome.UNAVAILABLE);
            if (input != expectedInput) return new Result(Outcome.PRICE_CHANGED, 0, 0, input, level, null);

            int value = Hondo.salePrice(get, exchange.amount(), 0);
            int buyBack = Hondo.buyBackValue(give, input);
            double giveDemand = give.demand() - (double) input / give.amount();
            double getDemand = get.demand() + (double) exchange.amount() / get.amount();
            setDemand(connection, give, giveDemand);
            setDemand(connection, get, getDemand);
            MarketRepository.logTrade(connection, player, give.material(), "SELL", input, buyBack, 0);
            MarketRepository.logTrade(connection, player, get.material(), "BUY", exchange.amount(), value, 0);
            Friendship after = recordTrade(connection, player, friend, "EXCHANGE", get.material(), exchange.amount(),
                    give.material(), input, value);
            changed.add(give.withDemand(giveDemand));
            changed.add(get.withDemand(getDemand));
            return new Result(Outcome.OK, value, 0, input, level, after);
        });
        if (result.outcome() == Outcome.OK) changed.forEach(Market::update);
        return result;
    }

    /** Buys the friendship offer of the level, once per player. */
    public static Result claim(@NotNull UUID player, int offerLevel, int expectedNet) {
        Hondo.FriendOffer offer = Hondo.offer(offerLevel);
        if (offer == null) return Result.of(Outcome.UNAVAILABLE);
        Result result = Database.inTransaction(connection -> {
            MarketItem item = MarketRepository.lock(connection, offer.material());
            if (item == null || !item.enabled() || !EndAccess.isAvailable(offer.material())) return Result.of(Outcome.UNAVAILABLE);
            Friend friend = lockFriend(connection, player);
            int level = Hondo.level(friend.points());
            if (level < offer.level()) return new Result(Outcome.LEVEL_TOO_LOW, 0, 0, 0, level, null);
            // the friendship row is locked, so no second claim of this player can slip in between
            if (Database.queryOne(connection, "SELECT 1 FROM hondo_claims WHERE player_uuid = ? AND level = ?",
                    row -> row.getInt(1), player, offer.level()) != null) {
                return new Result(Outcome.ALREADY_CLAIMED, 0, 0, 0, level, null);
            }

            int net = Hondo.offerPrice(item, offer);
            int tax = Taxes.taxOn(net, Hondo.TAX_CLASS);
            if (net != expectedNet) return new Result(Outcome.PRICE_CHANGED, net, tax, 0, level, null);
            if (!withdraw(connection, player, net + tax)) return new Result(Outcome.INSUFFICIENT_FUNDS, net, tax, 0, level, null);

            Database.update(connection, "INSERT INTO hondo_claims (player_uuid, level) VALUES (?, ?)", player, offer.level());
            MarketRepository.logTrade(connection, player, offer.material(), "BUY", offer.amount(), net, tax);
            Treasury.deposit(connection, Treasury.Source.TRADE_TAX, tax, player);
            Friendship after = recordTrade(connection, player, friend, "OFFER", offer.material(), offer.amount(), null, 0, net);
            return new Result(Outcome.OK, net, tax, 0, level, after);
        });
        if (result.outcome() == Outcome.OK) Treasury.committed(result.tax());
        return result;
    }

    /* goods the player did not receive (left during the booking, server stop): handed out at the next join */

    public static void addPending(@NotNull UUID player, @NotNull Material material, int quantity) {
        Database.update("INSERT INTO hondo_pending (player_uuid, material, quantity) VALUES (?, ?, ?)", player, material.name(), quantity);
    }

    public record Pending(Material material, int quantity) {}

    /** Removes and returns the player's pending goods. */
    public static List<Pending> takePending(@NotNull UUID player) {
        return Database.query("DELETE FROM hondo_pending WHERE player_uuid = ? RETURNING material, quantity",
                row -> new Pending(Material.matchMaterial(row.getString(1)), row.getInt(2)), player)
                .stream().filter(pending -> pending.material() != null).toList();
    }


    /* daily decay */

    public static @Nullable LocalDate evaluatedDay() {
        String value = Database.queryOne("SELECT value FROM market_meta WHERE key = ?", row -> row.getString(1), EVALUATED_DAY_KEY);
        return value == null ? null : LocalDate.parse(value);
    }

    /**
     * Evaluates the finished days up to {@code last}: a day without enough trades shrinks the points once.
     * While the server runs, every day is evaluated right after it ended, so only the day after the stored one can
     * have had trades; on the days after it the server was offline and nobody could trade, they are skipped.
     * The stored day is read and written in the same transaction, so no day is ever evaluated twice.
     * Returns the number of players who lost points.
     */
    public static int decay(@NotNull LocalDate last) {
        return Database.inTransaction(connection -> {
            String stored = Database.queryOne(connection, "SELECT value FROM market_meta WHERE key = ? FOR UPDATE",
                    row -> row.getString(1), EVALUATED_DAY_KEY);
            LocalDate done = stored == null ? null : LocalDate.parse(stored);
            if (done != null && !done.isBefore(last)) return 0;
            if (done == null) {
                // first start with Hondo's friendship: nothing to evaluate yet
                storeEvaluatedDay(connection, last);
                return 0;
            }
            LocalDate day = done.plusDays(1);

            Map<UUID, Double> points = new HashMap<>();
            Database.query(connection, "SELECT player_uuid, points FROM hondo_friends WHERE points > 0 FOR UPDATE", row -> {
                points.put(row.getObject(1, UUID.class), row.getDouble(2));
                return null;
            });
            Set<UUID> active = new HashSet<>();
            Database.query(connection, "SELECT player_uuid, COUNT(*), SUM(value) FROM hondo_trades WHERE created_at >= ? AND created_at < ? GROUP BY 1", row -> {
                if (row.getLong(2) >= Hondo.ACTIVE_TRADES || row.getLong(3) >= Hondo.ACTIVE_VOLUME) active.add(row.getObject(1, UUID.class));
                return null;
            }, startOf(day), startOf(day.plusDays(1)));

            int changed = 0;
            for (Map.Entry<UUID, Double> entry : points.entrySet()) {
                if (active.contains(entry.getKey())) continue;
                Database.update(connection, "UPDATE hondo_friends SET points = ? WHERE player_uuid = ?", Hondo.decayed(entry.getValue()), entry.getKey());
                changed++;
            }
            storeEvaluatedDay(connection, last);
            return changed;
        });
    }

    private static void storeEvaluatedDay(Connection connection, LocalDate day) throws SQLException {
        Database.update(connection, "INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                EVALUATED_DAY_KEY, day.toString());
    }

    private static Timestamp startOfToday() {
        return startOf(LocalDate.now(Market.ZONE));
    }

    private static Timestamp startOf(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(Market.ZONE).toInstant());
    }

}
