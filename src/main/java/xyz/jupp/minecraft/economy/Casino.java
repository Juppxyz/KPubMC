package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The casino's own till (Casinokasse), apart from the treasury: one row with the balance (casino_till, never below 0)
 * and a log of every booking (casino_ledger). A new season (an emptied till) starts with the config's casinoStartCapital.
 * Only the API ({@link EconomyApi}) books here: bets taken from a player in cash come in, wins handed out in cash go out,
 * never more than the till holds. So the casino can hand out at most its start capital plus what players lost there,
 * and one player at most the config's casinoDailyPayoutLimit in 24 hours (net). What the casino collects for the state
 * goes from the till into the treasury, only out of the surplus above the start capital.
 * Cash only on purpose: a win paid to an account would be a tax-free deposit, a bet from an account a tax-free
 * withdrawal; and money bound to a loan can never become cash (LOAN_LOCK at Basil), so it cannot be gambled either.
 * Database methods block (workers), load() runs in onEnable.
 */
public final class Casino {

    private Casino() {}

    public static final String START_CAPITAL = "START_CAPITAL";
    public static final String BET = "BET";
    public static final String WIN = "WIN";
    // a win the player could not be handed (left meanwhile, server stop): back into the till
    public static final String WIN_BACK = "WIN_BACK";
    // money of the till paid into the treasury (the casino's fees for the state)
    public static final String TO_TREASURY = "TO_TREASURY";

    /** win() / toTreasury(): the till (its surplus) cannot cover it. */
    public static final long EMPTY = -1;
    /** win(): the player reached the daily payout limit. */
    public static final long LIMIT = -2;

    private static final AtomicLong balance = new AtomicLong();

    /** Blocking, in onEnable after a season reset could have run: a new season's till gets the start capital, once. */
    public static void load() {
        int capital = startCapital();
        Database.inTransaction(connection -> {
            if (Database.update(connection, "INSERT INTO casino_till (id, balance) VALUES (1, ?) ON CONFLICT (id) DO NOTHING", capital) > 0
                    && capital > 0) {
                Database.update(connection, "INSERT INTO casino_ledger (kind, amount) VALUES (?, ?)", START_CAPITAL, capital);
                Main.getInstance().getSLF4JLogger().info("new season: the casino till starts with {} Schilling", capital);
            }
            return null;
        });
        Long value = Database.queryOne("SELECT balance FROM casino_till WHERE id = 1", row -> row.getLong(1));
        balance.set(value == null ? 0 : value);
    }

    public static int startCapital() {
        return ConfigManager.getManager().getCasinoStartCapital();
    }

    /** What the till holds (cached, follows every booking). */
    public static long balance() {
        return balance.get();
    }

    /** Worker: books a bet already taken from the player in cash; the till's new balance. Throws if nothing was booked. */
    public static long bet(@NotNull UUID player, long amount, @NotNull String game) {
        long left = Database.inTransaction(connection -> {
            Long after = Database.queryOne(connection, "UPDATE casino_till SET balance = balance + ? WHERE id = 1 RETURNING balance",
                    row -> row.getLong(1), amount);
            if (after == null) throw new IllegalStateException("the casino till is missing");
            Database.update(connection, "INSERT INTO casino_ledger (kind, amount, player_uuid, game) VALUES (?, ?, ?, ?)",
                    BET, amount, player, game);
            return after;
        });
        balance.addAndGet(amount);
        return left;
    }

    /**
     * Worker: pays a win out of the till; the new balance, or {@link #EMPTY} if the till cannot cover it, or
     * {@link #LIMIT} if the player would take more than the daily limit out of the till (nothing booked in both).
     */
    public static long win(@NotNull UUID player, long amount, @NotNull String game) {
        int limit = dailyLimit();
        long left = Database.inTransaction(connection -> {
            // the till row first: casino bookings run one after the other, two wins at once see each other in the limit
            Database.queryOne(connection, "SELECT balance FROM casino_till WHERE id = 1 FOR UPDATE", row -> row.getLong(1));
            if (limit > 0 && paidNet(connection, player) + amount > limit) return LIMIT;
            Long after = Database.queryOne(connection,
                    "UPDATE casino_till SET balance = balance - ? WHERE id = 1 AND balance >= ? RETURNING balance",
                    row -> row.getLong(1), amount, amount);
            if (after == null) return EMPTY;
            Database.update(connection, "INSERT INTO casino_ledger (kind, amount, player_uuid, game) VALUES (?, ?, ?, ?)",
                    WIN, -amount, player, game);
            return after;
        });
        if (left < 0) {
            if (left == LIMIT) {
                Main.getInstance().getSLF4JLogger().info("Casino win of {} for {} ({}) refused: daily payout limit of {} reached",
                        amount, player, game, limit);
            }
            return left;
        }
        balance.addAndGet(-amount);
        return left;
    }

    /** The most cash one player may take out of the till in 24 hours, net (wins minus bets); 0: no limit. */
    public static int dailyLimit() {
        return ConfigManager.getManager().getCasinoDailyPayoutLimit();
    }

    /** Blocking: what the player may still take out of the till now (Long.MAX_VALUE without a limit). */
    public static long payoutLeft(@NotNull UUID player) {
        int limit = dailyLimit();
        if (limit <= 0) return Long.MAX_VALUE;
        Long net = Database.queryOne(PAID_NET, row -> row.getLong(1), player, BET, WIN, WIN_BACK);
        return Math.max(0, limit - (net == null ? 0 : net));
    }

    // what the player got out of the till in the last 24 hours, net: wins minus wins given back minus bets
    private static final String PAID_NET = "SELECT COALESCE(-SUM(amount), 0) FROM casino_ledger WHERE player_uuid = ? "
            + "AND kind IN (?, ?, ?) AND created_at >= now() - interval '24 hours'";

    private static long paidNet(Connection connection, UUID player) throws SQLException {
        Long net = Database.queryOne(connection, PAID_NET, row -> row.getLong(1), player, BET, WIN, WIN_BACK);
        return net == null ? 0 : net;
    }

    /**
     * Worker: pays money of the till into the treasury (what the casino plugin collects for the state, e.g. exchange
     * fees), in one transaction. Only out of the surplus above the season's start capital, i.e. money players left at
     * the casino: the start capital itself was created for the casino and must not become state income. The new
     * balance, or {@link #EMPTY} if the surplus is too small (nothing booked).
     */
    public static long toTreasury(long amount, @NotNull String reason) {
        long left = Database.inTransaction(connection -> {
            Long till = Database.queryOne(connection, "SELECT balance FROM casino_till WHERE id = 1 FOR UPDATE", row -> row.getLong(1));
            if (till == null || till - startedWith(connection) < amount) return EMPTY;
            Long after = Database.queryOne(connection, "UPDATE casino_till SET balance = balance - ? WHERE id = 1 RETURNING balance",
                    row -> row.getLong(1), amount);
            if (after == null) return EMPTY;
            Database.update(connection, "INSERT INTO casino_ledger (kind, amount, game) VALUES (?, ?, ?)", TO_TREASURY, -amount, reason);
            Treasury.deposit(connection, Treasury.Source.CASINO, amount, null);
            return after;
        });
        if (left < 0) return left;
        balance.addAndGet(-amount);
        Treasury.committed(amount);
        return left;
    }

    /** Blocking: what may go to the treasury now (the till above the season's start capital). */
    public static long surplus() {
        Long start = Database.queryOne("SELECT COALESCE(SUM(amount), 0) FROM casino_ledger WHERE kind = ?", row -> row.getLong(1), START_CAPITAL);
        return Math.max(0, balance() - (start == null ? 0 : start));
    }

    // the start capital booked into this season's till (the config may have changed since)
    private static long startedWith(Connection connection) throws SQLException {
        Long start = Database.queryOne(connection, "SELECT COALESCE(SUM(amount), 0) FROM casino_ledger WHERE kind = ?",
                row -> row.getLong(1), START_CAPITAL);
        return start == null ? 0 : start;
    }

    /** Worker: a win that could not be handed out goes back into the till. */
    public static void winBack(@NotNull UUID player, long amount, @NotNull String game) {
        try {
            Database.inTransaction(connection -> {
                Database.update(connection, "UPDATE casino_till SET balance = balance + ? WHERE id = 1", amount);
                Database.update(connection, "INSERT INTO casino_ledger (kind, amount, player_uuid, game) VALUES (?, ?, ?, ?)",
                        WIN_BACK, amount, player, game);
                return null;
            });
            balance.addAndGet(amount);
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().error("Casino win of {} for {} ({}) was not handed out and could not go back into the till",
                    amount, player, game, e);
        }
    }

}
