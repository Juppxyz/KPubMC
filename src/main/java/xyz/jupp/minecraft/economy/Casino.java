package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The casino's own till (Casinokasse), apart from the treasury: one row with the balance (casino_till, never below 0)
 * and a log of every booking (casino_ledger). A new season (an emptied till) starts with the config's casinoStartCapital.
 * Only the API ({@link EconomyApi}) books here: bets taken from a player in cash come in, wins handed out in cash go out,
 * never more than the till holds. So the casino can hand out at most its start capital plus what players lost there.
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

    /** Worker: pays a win out of the till; the new balance, or -1 if the till cannot cover it (nothing booked). */
    public static long win(@NotNull UUID player, long amount, @NotNull String game) {
        Long left = Database.inTransaction(connection -> {
            // atomic: two wins at the same time can never take the till below 0
            Long after = Database.queryOne(connection,
                    "UPDATE casino_till SET balance = balance - ? WHERE id = 1 AND balance >= ? RETURNING balance",
                    row -> row.getLong(1), amount, amount);
            if (after == null) return null;
            Database.update(connection, "INSERT INTO casino_ledger (kind, amount, player_uuid, game) VALUES (?, ?, ?, ?)",
                    WIN, -amount, player, game);
            return after;
        });
        if (left == null) return -1;
        balance.addAndGet(-amount);
        return left;
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
