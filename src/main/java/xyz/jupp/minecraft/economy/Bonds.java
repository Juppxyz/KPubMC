package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.database.BankLog;
import xyz.jupp.minecraft.database.Database;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * State bonds at Basil, only while the state is broke: the money goes straight into the treasury (it counts as free
 * money, so bonds help the state out of the emergency) and the state pays back 115 % once its own money (the free
 * treasury minus all it owes for bonds) is above the recovery line, oldest bonds first. Table state_bonds, blocking.
 */
public final class Bonds {

    private Bonds() {}

    public static final int[] AMOUNTS = {1_000, 10_000};
    public static final double RETURN = 0.15;
    public static final int MAX_TOTAL = 25_000;

    public enum Outcome { OK, NOT_BROKE, LIMIT, INSUFFICIENT_FUNDS, UNAVAILABLE }

    /** The player's bonds that are not paid back yet: amount and what the state will pay. */
    public record Holding(long amount, long payout) {}

    public record Repaid(UUID player, int payout) {}

    /** What the state still owes for all bonds. */
    public static long owed() {
        Long owed = Database.queryOne("SELECT COALESCE(SUM(payout), 0) FROM state_bonds WHERE NOT repaid", row -> row.getLong(1));
        return owed == null ? 0 : owed;
    }

    public static Holding holding(@NotNull UUID player) {
        Holding holding = Database.queryOne("SELECT COALESCE(SUM(amount), 0), COALESCE(SUM(payout), 0) FROM state_bonds WHERE player_uuid = ? AND NOT repaid",
                row -> new Holding(row.getLong(1), row.getLong(2)), player);
        return holding == null ? new Holding(0, 0) : holding;
    }

    public static Outcome buy(@NotNull UUID player, int amount) {
        boolean offered = false;
        for (int option : AMOUNTS) offered |= option == amount;
        if (!offered) return Outcome.UNAVAILABLE;
        if (!Bankruptcy.isBroke()) return Outcome.NOT_BROKE;
        Outcome outcome = Database.inTransaction(connection -> {
            if (Database.queryOne(connection, "SELECT 1 FROM players WHERE uuid = ? FOR UPDATE", row -> 1, player) == null) return Outcome.UNAVAILABLE;
            Long held = Database.queryOne(connection, "SELECT COALESCE(SUM(amount), 0) FROM state_bonds WHERE player_uuid = ? AND NOT repaid",
                    row -> row.getLong(1), player);
            if (held != null && held + amount > MAX_TOTAL) return Outcome.LIMIT;
            // borrowed money cannot buy bonds either
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money - ? >= " + BankLog.LOAN_LOCK,
                    amount, player, amount, player) == 0) return Outcome.INSUFFICIENT_FUNDS;
            Database.update(connection, "INSERT INTO state_bonds (player_uuid, amount, payout) VALUES (?, ?, ?)",
                    player, amount, amount + (int) Math.round(amount * RETURN));
            Treasury.deposit(connection, Treasury.Source.BOND_SALE, amount, player);
            BankLog.add(connection, player, BankLog.BOND_BUY, -amount, null);
            return Outcome.OK;
        });
        if (outcome == Outcome.OK) Treasury.committed(amount);
        return outcome;
    }

    /** Worker: pays back what the recovered state can afford, oldest bonds first. */
    public static List<Repaid> repayDue() {
        if (Bankruptcy.isBroke()) return List.of();
        List<Repaid> repaid = new ArrayList<>();
        while (true) {
            Repaid one = Database.inTransaction(connection -> {
                // same lock as Basil's interest payouts, so two payouts never spend the same treasury money
                Database.queryOne(connection, "SELECT pg_advisory_xact_lock(?)", row -> 1, Bank.INTEREST_LOCK);
                Object[] bond = Database.queryOne(connection, "SELECT id, player_uuid, payout FROM state_bonds WHERE NOT repaid ORDER BY bought_at, id LIMIT 1 FOR UPDATE",
                        row -> new Object[]{row.getLong(1), row.getObject(2, UUID.class), row.getInt(3)});
                if (bond == null) return null;
                int payout = (int) bond[2];
                // only from the state's own money: the free treasury minus everything it still owes for bonds (this one
                // included), otherwise a bond would pay itself back out of its own money right after the recovery
                Bankruptcy.Level level = Bankruptcy.level(connection);
                Long owed = Database.queryOne(connection, "SELECT COALESCE(SUM(payout), 0) FROM state_bonds WHERE NOT repaid", row -> row.getLong(1));
                if (level.free() - (owed == null ? 0 : owed) < level.recoveredAt()) return null;
                UUID player = (UUID) bond[1];
                Database.update(connection, "UPDATE state_bonds SET repaid = TRUE, repaid_at = now() WHERE id = ?", (long) bond[0]);
                Treasury.withdraw(connection, Treasury.Source.BOND_REPAY, payout, player);
                Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", payout, player);
                BankLog.add(connection, player, BankLog.BOND_REPAY, payout, null);
                return new Repaid(player, payout);
            });
            if (one == null) return repaid;
            Treasury.committed(-one.payout());
            repaid.add(one);
        }
    }

}
