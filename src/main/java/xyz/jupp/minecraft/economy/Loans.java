package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.BankLog;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.database.DatabaseException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Basil's loans. The money is created for the loan (it does not come from the treasury) and disappears again when it
 * is paid back; only the treasury's share of the interest stays, in the treasury. Table bank_loans, every method is
 * blocking.
 * <p>
 * Rules: one loan per player (a unique index), paid back within 7 days, interest per started day, so paying early
 * costs less. The rate is Basil's current fixed deposit rate plus a share for the treasury. While the loan runs, the
 * borrowed amount plus its full interest ("locked") cannot be transferred, withdrawn as cash or put into a fixed
 * deposit, only spent. On the due day Basil takes it from the account; if that is not enough, he takes what is there,
 * the player is wanted until someone catches them, and gets no new loan until the apology (2.5 times the loan, minus
 * what was taken) is paid, which is only possible after the sentence.
 */
public final class Loans {

    private Loans() {}

    public static final int[] AMOUNTS = {1_000, 5_000, 10_000, 50_000, 100_000};
    public static final int DAYS = 7;
    static final double TREASURY_SHARE = 0.03;
    public static final double APOLOGY_FACTOR = 2.5;

    /** The largest loan for this account balance. */
    public static int maxFor(long balance) {
        if (balance > 50_000) return 100_000;
        if (balance > 25_000) return 50_000;
        if (balance > 7_500) return 10_000;
        return 5_000;
    }

    public enum State { OPEN, REPAID, DEFAULTED, SETTLED }

    public enum Outcome { OK, HAS_LOAN, NOT_ALLOWED, TOO_HIGH, RATE_CHANGED, INSUFFICIENT_FUNDS, WANTED, UNAVAILABLE }

    public record Loan(long id, int principal, double rate, double treasuryShare, int locked, State state, long paid,
                       Instant takenAt, Instant dueAt) {

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

        public long apologyLeft() {
            return Math.max(0, apology(principal) - paid);
        }
    }

    public static final int BOUNTY = 10_000;

    /**
     * The bounty for catching this player: normally 10.000, for an unpaid loan at most the loan money that is still
     * missing (the bounty is new money, a default on purpose must not pay off for a friend).
     */
    public static int bounty(@NotNull UUID player) {
        Loan loan = current(player);
        if (loan == null || loan.state() != State.DEFAULTED) return BOUNTY;
        return (int) Math.max(0, Math.min(BOUNTY, loan.principal() - loan.paid()));
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
                row.getTimestamp("taken_at").toInstant(), row.getTimestamp("due_at").toInstant());
    }

    /** The open or defaulted loan of the player, null if there is none. */
    public static @Nullable Loan current(@NotNull UUID player) {
        return Database.queryOne("SELECT * FROM bank_loans WHERE player_uuid = ? AND state IN ('OPEN', 'DEFAULTED')", Loans::map, player);
    }

    /** Takes a loan; expectedRate is the rate the player saw on the rules page. */
    public static Outcome take(@NotNull UUID player, int amount, double expectedRate) {
        boolean offered = false;
        for (int option : AMOUNTS) offered |= option == amount;
        if (!offered) return Outcome.UNAVAILABLE;
        try {
            return Database.inTransaction(connection -> {
                long[] account = Database.queryOne(connection, "SELECT money, CASE WHEN is_wanted OR jail THEN 1 ELSE 0 END FROM players WHERE uuid = ? FOR UPDATE",
                        row -> new long[]{row.getLong(1), row.getLong(2)}, player);
                if (account == null) return Outcome.UNAVAILABLE;
                if (account[1] == 1) return Outcome.WANTED;
                Loan active = Database.queryOne(connection, "SELECT * FROM bank_loans WHERE player_uuid = ? AND state IN ('OPEN', 'DEFAULTED')", Loans::map, player);
                if (active != null) return active.state() == State.DEFAULTED ? Outcome.NOT_ALLOWED : Outcome.HAS_LOAN;
                if (amount > maxFor(account[0])) return Outcome.TOO_HIGH;
                double rate = Bank.depositRate(connection) + TREASURY_SHARE;
                if (Math.abs(rate - expectedRate) > 1.0e-9) return Outcome.RATE_CHANGED;

                int locked = amount + (int) Math.ceil(amount * rate);
                Database.update(connection, "INSERT INTO bank_loans (player_uuid, principal, rate, treasury_share, locked, state, due_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'OPEN', ?)", player, amount, rate, TREASURY_SHARE, locked,
                        Timestamp.from(Instant.now().plus(Duration.ofDays(DAYS))));
                Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", amount, player);
                BankLog.add(connection, player, BankLog.LOAN_TAKE, amount, null);
                return Outcome.OK;
            });
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
            toTreasury[0] = loan.treasuryPart(now);
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
        Treasury.deposit(connection, Treasury.Source.LOAN_INTEREST, loan.treasuryPart(at), player);
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
            toTreasury[0] = penaltyPart(loan, left);
            Treasury.deposit(connection, Treasury.Source.LOAN_PENALTY, toTreasury[0], player);
            Database.update(connection, "UPDATE bank_loans SET state = 'SETTLED', paid = paid + ?, closed_at = now() WHERE id = ?", left, loan.id());
            BankLog.add(connection, player, BankLog.LOAN_APOLOGY, -left, null);
            return Outcome.OK;
        });
        if (outcome == Outcome.OK) Treasury.committed(toTreasury[0]);
        return outcome;
    }

    // payments on a defaulted loan: up to the principal the created money disappears again, the rest is a penalty for the treasury
    private static long penaltyPart(Loan loan, long payment) {
        long principalLeft = Math.max(0, loan.principal() - loan.paid());
        return Math.max(0, payment - principalLeft);
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
                // in jail: the release would clear a wanted status, so the default waits until they are out
                if (account[1] == 1) return null;
                Instant dueAt = loan.dueAt();
                if (settle(connection, player, loan, dueAt)) {
                    toTreasury[0] = loan.treasuryPart(dueAt);
                    return new Due(player, false, loan.debt(dueAt), 0);
                }
                // not enough: Basil takes what is there, the rest is owed as the apology
                long taken = account[0];
                Database.update(connection, "UPDATE players SET money = money - ?, is_wanted = TRUE, jail_end = 0 WHERE uuid = ?", taken, player);
                toTreasury[0] = penaltyPart(loan, taken);
                Treasury.deposit(connection, Treasury.Source.LOAN_PENALTY, toTreasury[0], player);
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
