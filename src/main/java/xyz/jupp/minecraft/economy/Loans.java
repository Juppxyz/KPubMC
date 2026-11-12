package xyz.jupp.minecraft.economy;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.building.Bauamt;
import xyz.jupp.minecraft.database.BankLog;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.database.DatabaseException;
import xyz.jupp.minecraft.jail.Bounties;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Basil's loans. The treasury lends its own money: out when the loan starts, back with the whole interest when it is
 * repaid; a default is a real loss of the state. So it lends only what it can spare: half of its free money above the
 * recovery line (nothing while broke). Table bank_loans, every method is blocking.
 * <p>
 * Who gets how much: only after MIN_HOURS of playtime and MIN_DAYS on the server; the first loan is small, every loan
 * repaid on time unlocks the next step ({@link #tierMax}); and never more than INCOME_FACTOR times what the player
 * earned from the state in the last INCOME_DAYS (shop sales, Bauamt grants, interest, bounties; money from other
 * players does not count, so a friend cannot vouch for an alt).
 * <p>
 * Rules: one loan per player (a unique index), paid back within 7 days, interest per started day, so paying early
 * costs less. The rate is Basil's current fixed deposit rate plus a share for the treasury. While the loan runs, the
 * borrowed amount plus its full interest ("locked") cannot go into a fixed deposit or a state bond (no interest on
 * created money); otherwise it is the player's to use. From REMIND_DAYS before the due day Basil reminds the player
 * once a day. On the due day Basil takes it from the account; if that is not enough, he takes what is there,
 * the player is wanted until someone catches them, and gets no new loan until the apology (2.5 times the loan, minus
 * what was taken) is paid, which is only possible after the sentence.
 */
public final class Loans {

    private Loans() {}

    public static final int[] AMOUNTS = {1_000, 5_000, 10_000, 50_000, 100_000};
    public static final int DAYS = 7;
    public static final int REMIND_DAYS = 3;
    static final double TREASURY_SHARE = 0.03;
    public static final double APOLOGY_FACTOR = 2.5;

    public static final int MIN_HOURS = 5;
    public static final int MIN_DAYS = 3;
    public static final int INCOME_DAYS = 7;
    public static final int INCOME_FACTOR = 3;
    // the treasury lends at most this share of its free money above the recovery line
    static final double LEND_SHARE = 0.5;

    /** The largest loan by the loans repaid on time and the hours played. */
    public static int tierMax(int repaid, long hours) {
        if (repaid >= 5 && hours >= 40) return 100_000;
        if (repaid >= 3 && hours >= 20) return 50_000;
        if (repaid >= 2) return 10_000;
        if (repaid >= 1) return 5_000;
        return 1_000;
    }

    /** What the server knows about the player's time on it (read on the main thread). */
    public record Standing(long playMinutes, long firstPlayed) {
        public long hours() {
            return playMinutes / 60;
        }

        public long days() {
            return firstPlayed <= 0 ? 0 : Duration.ofMillis(Math.max(0, System.currentTimeMillis() - firstPlayed)).toDays();
        }
    }

    /** Main thread. */
    public static Standing standing(@NotNull Player player) {
        return new Standing(player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L, player.getFirstPlayed());
    }

    /** What limits the loan: NEW (too little playtime or too few days), TIER, INCOME or TREASURY; FULL if nothing below the tier. */
    public enum Reason { FULL, NEW, TIER, INCOME, TREASURY }

    /** max: the largest of AMOUNTS the player may take now (0: none). */
    public record Limit(int max, Reason reason, int tierMax, int repaid, long income, long lendable) {}

    /** For the menu: the free treasury from the cached balance (no scan of the whole ledger per menu open). */
    public static Limit limit(@NotNull UUID player, @NotNull Standing standing) {
        return Database.withConnection(connection -> limit(connection, player, standing, false));
    }

    // exact: the free treasury from the ledger itself (for the loan, under the payout lock)
    static Limit limit(Connection connection, UUID player, Standing standing, boolean exact) throws SQLException {
        // a repaid loan counts only if it ran a while, at most one a day: taking and repaying at once builds no trust
        long[] row = Database.queryOne(connection, """
                SELECT (SELECT COUNT(DISTINCT taken_at::date) FROM bank_loans WHERE player_uuid = ? AND state = 'REPAID'
                            AND closed_at >= taken_at + interval '20 hours'),
                       (SELECT COALESCE(SUM(net), 0) FROM market_transactions WHERE player_uuid = ? AND kind = 'SELL' AND moves_money
                            AND created_at >= now() - make_interval(days => ?))
                       - (SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger WHERE player_uuid = ?
                            AND source IN ('BUILDING_GRANT', 'INTEREST', 'BOUNTY') AND created_at >= now() - make_interval(days => ?)),
                       (SELECT COALESCE(SUM(payout), 0) FROM state_bonds WHERE NOT repaid),
                       (SELECT COALESCE(SUM(principal), 0) FROM bank_loans WHERE funded AND state = 'OPEN'),
                       (SELECT COALESCE(SUM(amount), 0) FROM bank_deposits WHERE NOT paid_out AND in_treasury)""",
                result -> new long[]{result.getLong(1), result.getLong(2), result.getLong(3), result.getLong(4), result.getLong(5)},
                player, player, INCOME_DAYS, player, INCOME_DAYS);
        int repaid = row == null ? 0 : (int) row[0];
        long income = row == null ? 0 : Math.max(0, row[1]);
        long bondsOwed = row == null ? 0 : row[2];
        long lent = row == null ? 0 : row[3];
        Bankruptcy.Level level = exact ? Bankruptcy.level(connection) : Bankruptcy.lines(connection);
        long free = (exact ? level.free() : Treasury.balance() - (row == null ? 0 : row[4])) - bondsOwed;
        // half of the surplus above the recovery line, for all open loans together (what is lent out counts against it)
        long lendable = Math.max(0, Math.round((free + lent - level.recoveredAt()) * LEND_SHARE) - lent);
        int tier = tierMax(repaid, standing.hours());
        if (standing.hours() < MIN_HOURS || standing.days() < MIN_DAYS) return new Limit(0, Reason.NEW, tier, repaid, income, lendable);
        long incomeCap = income * INCOME_FACTOR;
        long cap = Math.min(tier, Math.min(incomeCap, lendable));
        int max = 0;
        for (int option : AMOUNTS) {
            if (option <= cap) max = option;
        }
        Reason reason = max >= tier ? Reason.FULL : lendable <= Math.min(tier, incomeCap) ? Reason.TREASURY
                : incomeCap < tier ? Reason.INCOME : Reason.TIER;
        return new Limit(max, reason, tier, repaid, income, lendable);
    }

    /** What the treasury has lent out and not got back yet (open loans). Blocking. */
    public static long lentOut() {
        Long lent = Database.queryOne("SELECT COALESCE(SUM(principal), 0) FROM bank_loans WHERE funded AND state = 'OPEN'", row -> row.getLong(1));
        return lent == null ? 0 : lent;
    }

    public enum State { OPEN, REPAID, DEFAULTED, SETTLED }

    public enum Outcome { OK, HAS_LOAN, NOT_ALLOWED, TOO_HIGH, TOO_NEW, TREASURY_EMPTY, RATE_CHANGED, INSUFFICIENT_FUNDS, WANTED, UNAVAILABLE }

    // caught: the warrant of the default was served, the missing principal caps no later bounty;
    // funded: the treasury lent the money (false for older loans of created money)
    public record Loan(long id, int principal, double rate, double treasuryShare, int locked, State state, long paid,
                       Instant takenAt, Instant dueAt, boolean caught, boolean funded) {

        // started days, at least one and at most the full term
        int days(Instant at) {
            long hours = Math.max(0, Duration.between(takenAt, at).toHours());
            return (int) Math.max(1, Math.min(DAYS, (hours + 23) / 24));
        }

        public int interest(Instant at) {
            return (int) Math.ceil(principal * rate * days(at) / DAYS);
        }

        int treasuryPart(Instant at) {
            return (int) Math.min(interest(at), Math.ceil(principal * treasuryShare * days(at) / DAYS));
        }

        /** What paying back costs right now. */
        public int debt(Instant at) {
            return principal + interest(at);
        }

        // what a repayment brings the treasury: everything if it lent the money, else only its share of the interest
        int toTreasury(Instant at) {
            return funded ? debt(at) : treasuryPart(at);
        }

        public long apologyLeft() {
            return Math.max(0, apology(principal) - paid);
        }
    }

    // the state's bounty: BOUNTY_START, BOUNTY_PER_HOUR more for every hour the wanted player was online on the run,
    // at most BOUNTY_MAX (a quick catch arranged with a friend brings little, a long hunt pays)
    public static final int BOUNTY_START = 2_500;
    public static final int BOUNTY_PER_HOUR = 500;
    public static final int BOUNTY_MAX = 5_000;
    // the bounty for a defaulted loan: this share of the missing principal, at least DEFAULT_BOUNTY_MIN
    static final double DEFAULT_BOUNTY_SHARE = 0.10;
    static final int DEFAULT_BOUNTY_MIN = 500;

    /**
     * The state's bounty for catching this player: BOUNTY_START, growing with the time on the run up to BOUNTY_MAX;
     * for an unpaid loan or an overdue Bauamt fine at most what is still missing of them (the treasury pays it, a default or an unpaid fine on purpose must not pay off for a
     * friend). wantedEnd: the end of the current warrant, 0 = until caught; the debts only cap such a warrant (an
     * old debt must not cap the bounty of a later escape), and each only the warrant it started (see
     * {@link #warrantServed}). Blocking.
     */
    public static int bounty(@NotNull UUID player, long wantedEnd) {
        Integer seconds = Database.queryOne("SELECT wanted_seconds FROM players WHERE uuid = ?", row -> row.getInt(1), player);
        int base = (int) Math.min(BOUNTY_MAX, BOUNTY_START + (long) BOUNTY_PER_HOUR * ((seconds == null ? 0 : seconds) / 3600));
        boolean defaulted = wantedEnd == 0 && defaultUnserved(player);
        Loan loan = defaulted ? current(player) : null;
        int fines = wantedEnd == 0 ? Bauamt.wantedBill(player) : 0;
        if (!defaulted && fines <= 0) return base;
        long missingPrincipal = loan != null ? Math.max(0, loan.principal() - loan.paid()) : 0;
        // the treasury lost the principal already: catching the defaulter pays only a share of it (a default on purpose
        // plus a friend's catch would otherwise cost the state twice)
        long loanPart = missingPrincipal == 0 ? 0
                : Math.min(missingPrincipal, Math.max(DEFAULT_BOUNTY_MIN, Math.round(missingPrincipal * DEFAULT_BOUNTY_SHARE)));
        long missing = loanPart + fines;
        return (int) Math.min(base, missing);
    }

    /** Blocking: the player defaulted and the warrant of that default was not served yet. */
    public static boolean defaultUnserved(@NotNull UUID player) {
        Loan loan = current(player);
        return loan != null && loan.state() == State.DEFAULTED && !loan.caught();
    }

    /**
     * Blocking, after the bounty of a catch was fixed: the wanted player went to jail, so the debts behind the warrant
     * (overdue Bauamt bills, a defaulted loan) cap no later bounty. They stay owed.
     */
    public static void warrantServed(@NotNull UUID player) {
        Bauamt.serveBills(player);
        Database.update("UPDATE bank_loans SET caught = TRUE WHERE player_uuid = ? AND state = 'DEFAULTED' AND NOT caught", player);
        Database.update("UPDATE players SET wanted_seconds = 0 WHERE uuid = ?", player);
        // money players put on the head and no hunter got: back to them
        Bounties.refundOpen(player);
    }

    public static long apology(int principal) {
        return (long) Math.ceil(principal * APOLOGY_FACTOR);
    }

    /** Rate of a new loan: Basil's fixed deposit rate plus the treasury's share. */
    public static double rateNow() {
        return Database.withConnection(connection -> Bank.depositRate(connection) + TREASURY_SHARE);
    }

    private static Loan map(java.sql.ResultSet row) throws SQLException {
        return new Loan(row.getLong("id"), row.getInt("principal"), row.getDouble("rate"), row.getDouble("treasury_share"),
                row.getInt("locked"), State.valueOf(row.getString("state")), row.getLong("paid"),
                row.getTimestamp("taken_at").toInstant(), row.getTimestamp("due_at").toInstant(), row.getBoolean("caught"),
                row.getBoolean("funded"));
    }

    /** The open or defaulted loan of the player, null if there is none. */
    /** An open loan due within REMIND_DAYS and the borrower's balance. */
    public record DueSoon(UUID player, Loan loan, long money) {}

    /** Open loans due within REMIND_DAYS: of one player, or of everyone with null. */
    public static List<DueSoon> dueSoon(@Nullable UUID player) {
        String sql = "SELECT l.*, p.money AS balance FROM bank_loans l JOIN players p ON p.uuid = l.player_uuid "
                + "WHERE l.state = 'OPEN' AND l.due_at <= now() + make_interval(days => ?)";
        Database.RowMapper<DueSoon> mapper = row -> new DueSoon(row.getObject("player_uuid", UUID.class), map(row), row.getLong("balance"));
        return player == null
                ? Database.query(sql, mapper, REMIND_DAYS)
                : Database.query(sql + " AND l.player_uuid = ?", mapper, REMIND_DAYS, player);
    }

    public static @Nullable Loan current(@NotNull UUID player) {
        return Database.queryOne("SELECT * FROM bank_loans WHERE player_uuid = ? AND state IN ('OPEN', 'DEFAULTED')", Loans::map, player);
    }

    /** Takes a loan; expectedRate is the rate the player saw on the rules page, standing read on the main thread. */
    public static Outcome take(@NotNull UUID player, int amount, double expectedRate, @NotNull Standing standing) {
        boolean offered = false;
        for (int option : AMOUNTS) offered |= option == amount;
        if (!offered) return Outcome.UNAVAILABLE;
        try {
            Outcome outcome = Database.inTransaction(connection -> {
                // the same lock as every other payout of the treasury: two of them never spend the same money
                Database.queryOne(connection, "SELECT pg_advisory_xact_lock(?)", row -> 1, Bank.INTEREST_LOCK);
                long[] account = Database.queryOne(connection, "SELECT money, CASE WHEN is_wanted OR jail THEN 1 ELSE 0 END FROM players WHERE uuid = ? FOR UPDATE",
                        row -> new long[]{row.getLong(1), row.getLong(2)}, player);
                if (account == null) return Outcome.UNAVAILABLE;
                if (account[1] == 1) return Outcome.WANTED;
                Loan active = Database.queryOne(connection, "SELECT * FROM bank_loans WHERE player_uuid = ? AND state IN ('OPEN', 'DEFAULTED')", Loans::map, player);
                if (active != null) return active.state() == State.DEFAULTED ? Outcome.NOT_ALLOWED : Outcome.HAS_LOAN;
                Limit limit = limit(connection, player, standing, true);
                if (limit.reason() == Reason.NEW) return Outcome.TOO_NEW;
                if (amount > limit.max()) return amount > limit.lendable() ? Outcome.TREASURY_EMPTY : Outcome.TOO_HIGH;
                double rate = Bank.depositRate(connection) + TREASURY_SHARE;
                if (Math.abs(rate - expectedRate) > 1.0e-9) return Outcome.RATE_CHANGED;

                int locked = amount + (int) Math.ceil(amount * rate);
                Database.update(connection, "INSERT INTO bank_loans (player_uuid, principal, rate, treasury_share, locked, state, due_at, funded) "
                        + "VALUES (?, ?, ?, ?, ?, 'OPEN', ?, TRUE)", player, amount, rate, TREASURY_SHARE, locked,
                        Timestamp.from(Instant.now().plus(Duration.ofDays(DAYS))));
                Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", amount, player);
                Treasury.withdraw(connection, Treasury.Source.LOAN_OUT, amount, player);
                BankLog.add(connection, player, BankLog.LOAN_TAKE, amount, null);
                return Outcome.OK;
            });
            if (outcome == Outcome.OK) Treasury.committed(-amount);
            return outcome;
        } catch (DatabaseException e) {
            // the unique index: a second loan started at the same moment
            if (current(player) != null) return Outcome.HAS_LOAN;
            throw e;
        }
    }

    /** Pays the open loan back now (principal plus the interest of the started days). */
    public static Outcome repay(@NotNull UUID player) {
        int[] toTreasury = {0};
        Outcome outcome = Database.inTransaction(connection -> {
            Loan loan = Database.queryOne(connection, "SELECT * FROM bank_loans WHERE player_uuid = ? AND state = 'OPEN' FOR UPDATE", Loans::map, player);
            if (loan == null) return Outcome.UNAVAILABLE;
            Instant now = Instant.now();
            if (!settle(connection, player, loan, now)) return Outcome.INSUFFICIENT_FUNDS;
            toTreasury[0] = loan.toTreasury(now);
            return Outcome.OK;
        });
        if (outcome == Outcome.OK) Treasury.committed(toTreasury[0]);
        return outcome;
    }

    // pays an open loan back from the account; false (nothing booked) if the money is not enough
    private static boolean settle(Connection connection, UUID player, Loan loan, Instant at) throws SQLException {
        int debt = loan.debt(at);
        if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?", debt, player, debt) == 0) {
            return false;
        }
        if (loan.funded()) {
            // the lent money goes back to the treasury, the whole interest with it
            Treasury.deposit(connection, Treasury.Source.LOAN_BACK, loan.principal(), player);
            Treasury.deposit(connection, Treasury.Source.LOAN_INTEREST, loan.interest(at), player);
        } else {
            Treasury.deposit(connection, Treasury.Source.LOAN_INTEREST, loan.treasuryPart(at), player);
        }
        Database.update(connection, "UPDATE bank_loans SET state = 'REPAID', paid = ?, closed_at = now() WHERE id = ?", debt, loan.id());
        BankLog.add(connection, player, BankLog.LOAN_REPAY, -debt, String.valueOf(loan.interest(at)));
        return true;
    }

    /** After a default and the sentence: pays the rest of the apology, then loans are possible again. */
    public static Outcome apologize(@NotNull UUID player) {
        long[] toTreasury = {0};
        Outcome outcome = Database.inTransaction(connection -> {
            Loan loan = Database.queryOne(connection, "SELECT * FROM bank_loans WHERE player_uuid = ? AND state = 'DEFAULTED' FOR UPDATE", Loans::map, player);
            if (loan == null) return Outcome.UNAVAILABLE;
            Long free = Database.queryOne(connection, "SELECT CASE WHEN is_wanted OR jail THEN 1 ELSE 0 END FROM players WHERE uuid = ?", row -> row.getLong(1), player);
            if (free == null || free == 1) return Outcome.WANTED;
            long left = loan.apologyLeft();
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?", left, player, left) == 0) {
                return Outcome.INSUFFICIENT_FUNDS;
            }
            toTreasury[0] = bookDefaultPayment(connection, player, loan, left);
            Database.update(connection, "UPDATE bank_loans SET state = 'SETTLED', paid = paid + ?, closed_at = now() WHERE id = ?", left, loan.id());
            BankLog.add(connection, player, BankLog.LOAN_APOLOGY, -left, null);
            return Outcome.OK;
        });
        if (outcome == Outcome.OK) Treasury.committed(toTreasury[0]);
        return outcome;
    }

    /**
     * A payment on a defaulted loan: up to the missing principal it pays the loan back (to the treasury that lent it; the
     * created money of an older loan just disappears), the rest is a penalty for the treasury. Returns what the treasury got.
     */
    private static long bookDefaultPayment(Connection connection, UUID player, Loan loan, long payment) throws SQLException {
        long principalLeft = Math.max(0, loan.principal() - loan.paid());
        long back = Math.min(payment, principalLeft);
        long penalty = payment - back;
        if (loan.funded()) Treasury.deposit(connection, Treasury.Source.LOAN_BACK, back, player);
        Treasury.deposit(connection, Treasury.Source.LOAN_PENALTY, penalty, player);
        return (loan.funded() ? back : 0) + penalty;
    }


    /* due loans (worker, every few minutes) */

    public record Due(UUID player, boolean defaulted, int amount, long apologyLeft) {}

    /** Collects every due loan: paid from the account if enough is there, otherwise defaulted (wanted, no new loans). */
    public static List<Due> processDue() {
        List<Long> ids = Database.query("SELECT id FROM bank_loans WHERE state = 'OPEN' AND due_at <= now()", row -> row.getLong(1));
        List<Due> results = new ArrayList<>();
        for (long id : ids) {
            long[] toTreasury = {0};
            Due due = Database.inTransaction(connection -> {
                Loan loan = Database.queryOne(connection, "SELECT * FROM bank_loans WHERE id = ? AND state = 'OPEN' AND due_at <= now() FOR UPDATE", Loans::map, id);
                if (loan == null) return null;
                UUID player = Database.queryOne(connection, "SELECT player_uuid FROM bank_loans WHERE id = ?", row -> row.getObject(1, UUID.class), id);
                long[] account = Database.queryOne(connection, "SELECT money, CASE WHEN jail THEN 1 ELSE 0 END FROM players WHERE uuid = ? FOR UPDATE",
                        row -> new long[]{row.getLong(1), row.getLong(2)}, player);
                if (account == null) return null;
                Instant dueAt = loan.dueAt();
                if (settle(connection, player, loan, dueAt)) {
                    toTreasury[0] = loan.toTreasury(dueAt);
                    return new Due(player, false, loan.debt(dueAt), 0);
                }
                // in jail: the release would clear a wanted status, so the default waits until they are out
                if (account[1] == 1) return null;
                // not enough: Basil takes what is there, the rest is owed as the apology
                long taken = account[0];
                Database.update(connection, "UPDATE players SET money = money - ?, is_wanted = TRUE, jail_end = 0, wanted_seconds = CASE WHEN is_wanted AND NOT jail AND (jail_end = 0 OR jail_end > ?) THEN wanted_seconds ELSE 0 END WHERE uuid = ?",
                        taken, System.currentTimeMillis(), player);
                toTreasury[0] = bookDefaultPayment(connection, player, loan, taken);
                Database.update(connection, "UPDATE bank_loans SET state = 'DEFAULTED', paid = ?, closed_at = now() WHERE id = ?", taken, id);
                BankLog.add(connection, player, BankLog.LOAN_DEFAULT, -taken, null);
                return new Due(player, true, (int) taken, Math.max(0, apology(loan.principal()) - taken));
            });
            if (due == null) continue;
            if (toTreasury[0] > 0) Treasury.committed(toTreasury[0]);
            results.add(due);
        }
        return results;
    }

}
