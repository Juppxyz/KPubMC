package xyz.jupp.minecraft.economy;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.BankLog;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Tasks;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Basil's bank: cash deposits, fixed deposits with the state treasury, the vault and the statement.
 * Tables bank_log, bank_deposits and bank_vaults. Every method is blocking.
 * <p>
 * Basil has no money of his own: a fixed deposit goes into the treasury and the treasury pays it back with interest.
 * The deposited amounts are owed to the players, so only the free treasury (balance minus owed deposits) counts for
 * the interest: never more than that is paid, and deposits do not raise their own budget. The budget: per week at most
 * half of what the treasury took in during the last 7 days and at most a quarter of its free balance. The rate follows
 * how much of that budget is still free: 8 % while nothing is promised, down to 2 % when it is used up; it is fixed
 * when the deposit starts.
 */
public final class Bank {

    private Bank() {}

    public static final String PREFIX = Main.getFinanceVillagerFredName() + " §7» §f";

    public static final int[] WITHDRAW_AMOUNTS = {100, 1_000, 5_000, 10_000};
    public static final int[] TERM_AMOUNTS = {1_000, 10_000, 50_000};
    public static final int TERM_DAYS = 7;
    public static final int MAX_TERMS = 3;
    static final double MIN_RATE = 0.02;
    static final double MAX_RATE = 0.08;
    // the interest budget: shares of last week's income and of the balance of the treasury
    static final double BUDGET_INCOME_SHARE = 0.5;
    static final double BUDGET_BALANCE_SHARE = 0.25;
    // one player may use at most this share of the budget
    static final double PLAYER_BUDGET_SHARE = 0.5;
    public static final int VAULT_FEE = 250;

    // serializes the interest payouts, so two of them never spend the same treasury money
    static final long INTEREST_LOCK = 0x4B7075624261L;
    private static final long PAYOUT_PERIOD_TICKS = 20L * 60 * 5;


    /** Blocking, in onEnable before Treasury.load: books fixed deposits from before into the treasury (once). */
    public static void load() {
        int booked = Database.inTransaction(connection -> {
            List<Object[]> open = Database.query(connection, "UPDATE bank_deposits SET in_treasury = TRUE WHERE NOT paid_out AND NOT in_treasury "
                    + "RETURNING player_uuid, amount", row -> new Object[]{row.getObject(1, UUID.class), row.getInt(2)});
            for (Object[] deposit : open) Treasury.deposit(connection, Treasury.Source.FIXED_DEPOSIT, (int) deposit[1], (UUID) deposit[0]);
            return open.size();
        });
        if (booked > 0) Main.getInstance().getSLF4JLogger().info("booked {} older fixed deposits into the treasury", booked);
    }

    /** The deposited amounts the treasury owes the players. */
    public static long owedDeposits() {
        Long owed = Database.queryOne("SELECT COALESCE(SUM(amount), 0) FROM bank_deposits WHERE NOT paid_out AND in_treasury", row -> row.getLong(1));
        return owed == null ? 0 : owed;
    }


    /* cash */

    /** Credits deposited cash (already taken from the player); false if the player row is missing. */
    public static boolean deposit(@NotNull UUID player, long amount) {
        return Database.inTransaction(connection -> {
            if (Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", amount, player) == 0) return false;
            BankLog.add(connection, player, BankLog.DEPOSIT, amount, null);
            return true;
        });
    }


    /**
     * Main thread, after a withdrawal: the notes go to the player who is online now (a Player object from before a
     * logout is never saved again); if they left meanwhile, the cash goes straight back to the account.
     */
    public static void handOut(@NotNull UUID player, long cash) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            Cash.give(online, cash);
            return;
        }
        Tasks.async(() -> {
            try {
                deposit(player, cash);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().error("Cash of {} ({}) could not go back to the account", player, cash, e);
            }
        });
    }


    /* statement */

    public record Entry(Instant at, String kind, long amount, @Nullable String note) {}

    /** The last bookings: bank log, trades that moved money, death and nether taxes. */
    public static List<Entry> statement(@NotNull UUID player, int limit) {
        return Database.query("""
                SELECT at, kind, amount, note FROM (
                    SELECT created_at AS at, kind, amount, note FROM bank_log WHERE player_uuid = ?
                    UNION ALL
                    SELECT created_at, 'MARKET_' || kind, CASE WHEN kind = 'BUY' THEN -(net + tax) ELSE net END, material
                        FROM market_transactions WHERE player_uuid = ? AND moves_money
                    UNION ALL
                    SELECT created_at, 'TAX_' || source, -amount, NULL
                        FROM treasury_ledger WHERE player_uuid = ? AND source IN ('DEATH_TAX', 'NETHER_TAX')
                ) bookings ORDER BY at DESC LIMIT ?""",
                row -> new Entry(row.getTimestamp(1).toInstant(), row.getString(2), row.getLong(3), row.getString(4)),
                player, player, player, limit);
    }


    /* fixed deposits */

    public enum Outcome { OK, INSUFFICIENT_FUNDS, LIMIT, CLOSED, UNAVAILABLE }

    /**
     * What Basil offers right now: the rate for a new deposit and the most a player can still put in
     * (0 if the treasury took in too little; playerLimited if it is the player's own share that is used up).
     */
    public record Offer(double rate, long capacity, boolean playerLimited) {

        public boolean accepts(int amount) {
            return rate > 0 && amount <= capacity;
        }
    }

    private record Budget(double budget, double promised, double playerPromised) {

        double rate() {
            double used = budget <= 0 ? 1 : Math.min(1, promised / budget);
            // in steps of half a percent, nobody needs more precision
            return Math.round((MIN_RATE + (MAX_RATE - MIN_RATE) * (1 - used)) * 200) / 200.0;
        }

        Offer offer() {
            double rate = rate();
            long total = budget <= promised ? 0 : (long) ((budget - promised) / rate);
            double playerBudget = budget * PLAYER_BUDGET_SHARE;
            long own = playerBudget <= playerPromised ? 0 : (long) ((playerBudget - playerPromised) / rate);
            return new Offer(rate, Math.min(total, own), own < total);
        }
    }

    private static Budget budget(java.sql.Connection connection, UUID player) throws java.sql.SQLException {
        return Database.queryOne(connection, """
                SELECT (SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger)
                           - (SELECT COALESCE(SUM(amount), 0) FROM bank_deposits WHERE NOT paid_out AND in_treasury),
                       (SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger
                            WHERE amount > 0 AND source NOT IN ('INTEREST', 'FIXED_DEPOSIT', 'BOND_SALE') AND created_at >= now() - make_interval(days => ?)),
                       (SELECT COALESCE(SUM(amount * rate), 0) FROM bank_deposits WHERE NOT paid_out),
                       (SELECT COALESCE(SUM(amount * rate), 0) FROM bank_deposits WHERE NOT paid_out AND player_uuid = ?)""",
                row -> {
                    double balance = Math.max(0, row.getLong(1));
                    double income = Math.max(0, row.getLong(2));
                    return new Budget(Math.min(income * BUDGET_INCOME_SHARE, balance * BUDGET_BALANCE_SHARE), row.getDouble(3), row.getDouble(4));
                }, TERM_DAYS, player);
    }

    // the rate a fixed deposit would get right now (a loan adds the treasury's share)
    static double depositRate(java.sql.Connection connection) throws java.sql.SQLException {
        return budget(connection, new UUID(0, 0)).rate();
    }

    public static Offer offer(@NotNull UUID player) {
        return Database.withConnection(connection -> budget(connection, player).offer());
    }

    public record Term(long id, int amount, double rate, Instant endsAt) {

        public int interest() {
            return (int) Math.round(amount * rate);
        }
    }

    public record Payout(UUID player, int amount, int interest) {}

    public static List<Term> terms(@NotNull UUID player) {
        return Database.query("SELECT id, amount, rate, ends_at FROM bank_deposits WHERE player_uuid = ? AND NOT paid_out ORDER BY ends_at",
                row -> new Term(row.getLong(1), row.getInt(2), row.getDouble(3), row.getTimestamp(4).toInstant()), player);
    }

    public static Outcome startTerm(@NotNull UUID player, int amount) {
        boolean allowed = false;
        for (int option : TERM_AMOUNTS) allowed |= option == amount;
        if (!allowed) return Outcome.UNAVAILABLE;
        Outcome outcome = Database.inTransaction(connection -> {
            // one start at a time (same lock as the payouts), so the budget is never promised twice
            Database.queryOne(connection, "SELECT pg_advisory_xact_lock(?)", row -> 1, INTEREST_LOCK);
            if (Database.queryOne(connection, "SELECT 1 FROM players WHERE uuid = ?", row -> 1, player) == null) {
                return Outcome.UNAVAILABLE;
            }
            Long running = Database.queryOne(connection, "SELECT COUNT(*) FROM bank_deposits WHERE player_uuid = ? AND NOT paid_out",
                    row -> row.getLong(1), player);
            if (running != null && running >= MAX_TERMS) return Outcome.LIMIT;
            Offer offer = budget(connection, player).offer();
            if (!offer.accepts(amount)) return offer.playerLimited() ? Outcome.LIMIT : Outcome.CLOSED;
            // borrowed money cannot be put into a fixed deposit
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money - ? >= " + BankLog.LOAN_LOCK,
                    amount, player, amount, player) == 0) {
                return Outcome.INSUFFICIENT_FUNDS;
            }
            Database.update(connection, "INSERT INTO bank_deposits (player_uuid, amount, rate, ends_at, in_treasury) VALUES (?, ?, ?, ?, TRUE)",
                    player, amount, offer.rate(), Timestamp.from(Instant.now().plus(Duration.ofDays(TERM_DAYS))));
            Treasury.deposit(connection, Treasury.Source.FIXED_DEPOSIT, amount, player);
            BankLog.add(connection, player, BankLog.TERM_START, -amount, null);
            return Outcome.OK;
        });
        if (outcome == Outcome.OK) Treasury.committed(amount);
        return outcome;
    }

    /** Ends a fixed deposit early: the treasury pays the amount back, without interest. */
    public static boolean cancelTerm(@NotNull UUID player, long id) {
        long fromTreasury = Database.inTransaction(connection -> {
            long[] deposit = Database.queryOne(connection, "UPDATE bank_deposits SET paid_out = TRUE, early = TRUE, closed_at = now() "
                            + "WHERE id = ? AND player_uuid = ? AND NOT paid_out RETURNING amount, in_treasury",
                    row -> new long[]{row.getInt(1), row.getBoolean(2) ? 1 : 0}, id, player);
            if (deposit == null) return -1L;
            if (deposit[1] == 1) Treasury.withdraw(connection, Treasury.Source.FIXED_DEPOSIT, deposit[0], player);
            Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", deposit[0], player);
            BankLog.add(connection, player, BankLog.TERM_CANCEL, deposit[0], null);
            return deposit[1] == 1 ? deposit[0] : 0L;
        });
        if (fromTreasury > 0) Treasury.committed(-fromTreasury);
        return fromTreasury >= 0;
    }

    /** Pays out every due fixed deposit (of one player, or of everyone with null). */
    public static List<Payout> payoutDue(@Nullable UUID player) {
        List<Long> due = player == null
                ? Database.query("SELECT id FROM bank_deposits WHERE NOT paid_out AND ends_at <= now()", row -> row.getLong(1))
                : Database.query("SELECT id FROM bank_deposits WHERE NOT paid_out AND ends_at <= now() AND player_uuid = ?", row -> row.getLong(1), player);
        List<Payout> payouts = new ArrayList<>();
        for (long id : due) {
            Payout payout = payout(id);
            if (payout != null) payouts.add(payout);
        }
        return payouts;
    }

    private record Due(UUID player, int amount, int interest, boolean inTreasury) {}

    // the treasury pays the amount back plus the interest, the interest never more than its free balance
    private static @Nullable Payout payout(long id) {
        Due paid = Database.inTransaction(connection -> {
            Database.queryOne(connection, "SELECT pg_advisory_xact_lock(?)", row -> 1, INTEREST_LOCK);
            Due due = Database.queryOne(connection, "UPDATE bank_deposits SET paid_out = TRUE, closed_at = now() "
                            + "WHERE id = ? AND NOT paid_out AND ends_at <= now() RETURNING player_uuid, amount, rate, in_treasury",
                    row -> new Due(row.getObject(1, UUID.class), row.getInt(2), (int) Math.round(row.getInt(2) * row.getDouble(3)), row.getBoolean(4)), id);
            if (due == null) return null;
            if (due.inTreasury()) Treasury.withdraw(connection, Treasury.Source.FIXED_DEPOSIT, due.amount(), due.player());
            // free: what is left after every deposit still owed to the players
            Long free = Database.queryOne(connection, "SELECT (SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger) "
                    + "- (SELECT COALESCE(SUM(amount), 0) FROM bank_deposits WHERE NOT paid_out AND in_treasury)", row -> row.getLong(1));
            int interest = (int) Math.max(0, Math.min(due.interest(), free == null ? 0 : free));
            if (interest > 0) Treasury.withdraw(connection, Treasury.Source.INTEREST, interest, due.player());
            Database.update(connection, "UPDATE bank_deposits SET interest = ? WHERE id = ?", interest, id);
            Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", due.amount() + interest, due.player());
            BankLog.add(connection, due.player(), BankLog.TERM_PAYOUT, due.amount() + interest, String.valueOf(interest));
            return new Due(due.player(), due.amount(), interest, due.inTreasury());
        });
        if (paid == null) return null;
        Treasury.committed(-(paid.interest() + (paid.inTreasury() ? paid.amount() : 0)));
        return new Payout(paid.player(), paid.amount(), paid.interest());
    }

    /** Checks for due fixed deposits every few minutes, also for players who are offline. */
    public static void startTasks() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), () -> {
            List<Payout> payouts;
            List<Loans.Due> loans;
            try {
                payouts = payoutDue(null);
                loans = Loans.processDue();
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Fixed deposit or loan processing failed: {}", e.toString());
                return;
            }
            if (!payouts.isEmpty() || !loans.isEmpty()) MainThread.run(() -> {
                payouts.forEach(Bank::announce);
                loans.forEach(Bank::announceLoan);
            });
        }, 20L * 40, PAYOUT_PERIOD_TICKS);
    }

    // main thread
    static void announce(@NotNull Payout payout) {
        Player player = Bukkit.getPlayer(payout.player());
        if (player == null) return;
        player.sendMessage(PREFIX + "Dein Festgeld ist fällig: §a+" + (payout.amount() + payout.interest()) + " Schilling §8(davon "
                + payout.interest() + " Zinsen)");
    }


    // main thread: a due loan was collected, or it defaulted and the player is wanted until someone catches them
    static void announceLoan(@NotNull Loans.Due due) {
        Player player = Bukkit.getPlayer(due.player());
        if (!due.defaulted()) {
            if (player != null) player.sendMessage(PREFIX + "Dein Kredit war fällig, ich habe §c" + due.amount() + " Schilling §feingezogen. Danke!");
            return;
        }
        String reason = "Kredit nicht zurückgezahlt";
        if (player != null) {
            JailHandler.markWantedUntilCaught(player, reason);
            player.sendMessage(PREFIX + "§cDu hast deinen Kredit nicht zurückgezahlt. Ich habe " + due.amount() + " Schilling eingezogen,");
            player.sendMessage(PREFIX + "§cjetzt wirst du gesucht. Neue Kredite erst nach deiner Strafe und einer Entschuldigung.");
        } else {
            String name = Bukkit.getOfflinePlayer(due.player()).getName();
            JailHandler.broadcastWanted(name == null ? "?" : name, reason);
        }
    }


    // main thread: the recovered state paid a bond back
    static void announceBond(@NotNull Bonds.Repaid repaid) {
        Player player = Bukkit.getPlayer(repaid.player());
        if (player != null) player.sendMessage(PREFIX + "Der Staat zahlt deine Anleihe zurück: §a+" + repaid.payout() + " Schilling§f. Danke für deine Hilfe!");
    }


    /* vault */

    /** Takes the vault fee (to the treasury); false if the money is not enough. */
    public static boolean chargeVaultFee(@NotNull UUID player) {
        boolean paid = Database.inTransaction(connection -> {
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?", VAULT_FEE, player, VAULT_FEE) == 0) {
                return false;
            }
            Treasury.deposit(connection, Treasury.Source.VAULT_FEE, VAULT_FEE, player);
            BankLog.add(connection, player, BankLog.VAULT_FEE, -VAULT_FEE, null);
            return true;
        });
        if (paid) Treasury.committed(VAULT_FEE);
        return paid;
    }

    static byte @Nullable [] loadVault(@NotNull UUID player) {
        return Database.queryOne("SELECT items FROM bank_vaults WHERE player_uuid = ?", row -> row.getBytes(1), player);
    }

    static void saveVault(@NotNull UUID player, byte @NotNull [] items) {
        Database.update("INSERT INTO bank_vaults (player_uuid, items) VALUES (?, ?) "
                + "ON CONFLICT (player_uuid) DO UPDATE SET items = EXCLUDED.items, updated_at = now()", player, items);
    }

}
