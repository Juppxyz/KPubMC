package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.BankLog;
import xyz.jupp.minecraft.database.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A team's shared money, the "Team-Kasse" (column teams.treasury): every member can pay in, the owner and the vices
 * can take money out and pay the team warp from it. Taking out costs the tax of a cash withdrawal, otherwise the team
 * treasury would be a way around the death and nether tax. Every booking lands in team_ledger, the player's side also
 * in Basil's statement. Roles are checked in the database, in the same transaction. Every method is blocking.
 */
public final class TeamBank {

    private TeamBank() {}

    public static final int[] AMOUNTS = {100, 1_000, 10_000};

    // team_ledger kinds
    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAW = "WITHDRAW";
    public static final String WARP_SET = "WARP_SET";
    public static final String WARP_MOVE = "WARP_MOVE";

    public enum Outcome { OK, INSUFFICIENT_FUNDS, NOT_ALLOWED }

    public record Payout(Outcome outcome, long paid, long tax) {}

    // name: the member's stored name, null for someone who left the team since
    public record Entry(Instant at, @Nullable String name, String kind, long amount) {}

    public static long balance(@NotNull String teamID) {
        Long balance = Database.queryOne("SELECT treasury FROM teams WHERE team_id = ?", row -> row.getLong(1), teamID);
        return balance == null ? 0 : balance;
    }

    /** The player's money into the team treasury (members only). */
    public static Outcome deposit(@NotNull UUID player, @NotNull String teamID, long amount) {
        if (amount <= 0) return Outcome.NOT_ALLOWED;
        Outcome outcome = Database.inTransaction(connection -> {
            if (role(connection, teamID, player) == null) return Outcome.NOT_ALLOWED;
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?", amount, player, amount) == 0) {
                return Outcome.INSUFFICIENT_FUNDS;
            }
            Database.update(connection, "UPDATE teams SET treasury = treasury + ? WHERE team_id = ?", amount, teamID);
            book(connection, teamID, player, DEPOSIT, amount);
            BankLog.add(connection, player, BankLog.TEAM_DEPOSIT, -amount, null);
            return Outcome.OK;
        });
        if (outcome == Outcome.OK) Main.getInstance().getSLF4JLogger().info("team treasury {} +{} from {}", teamID, amount, player);
        return outcome;
    }

    /** Money from the team treasury to the owner or a vice, minus the tax of a cash withdrawal (to the state treasury). */
    public static Payout withdraw(@NotNull UUID player, @NotNull String teamID, int amount) {
        if (amount <= 0) return new Payout(Outcome.NOT_ALLOWED, 0, 0);
        int tax = Taxes.taxOn(amount, TaxClass.STANDARD);
        long paid = amount - tax;
        Outcome outcome = Database.inTransaction(connection -> {
            String role = role(connection, teamID, player);
            if (!"owner".equals(role) && !"vice".equals(role)) return Outcome.NOT_ALLOWED;
            if (Database.update(connection, "UPDATE teams SET treasury = treasury - ? WHERE team_id = ? AND treasury >= ?", amount, teamID, amount) == 0) {
                return Outcome.INSUFFICIENT_FUNDS;
            }
            Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", paid, player);
            Treasury.deposit(connection, Treasury.Source.WITHDRAW_TAX, tax, player);
            book(connection, teamID, player, WITHDRAW, -amount);
            BankLog.add(connection, player, BankLog.TEAM_WITHDRAW, paid, null);
            return Outcome.OK;
        });
        if (outcome != Outcome.OK) return new Payout(outcome, 0, 0);
        Treasury.committed(tax);
        Main.getInstance().getSLF4JLogger().info("team treasury {} -{} to {} ({} tax)", teamID, amount, player, tax);
        return new Payout(Outcome.OK, paid, tax);
    }

    /** Inside a transaction: a dissolved team's whole treasury to its last member, minus the tax of a cash withdrawal. */
    public static Payout payOutAll(@NotNull Connection connection, @NotNull String teamID, @NotNull UUID player) throws SQLException {
        Long balance = Database.queryOne(connection, "SELECT treasury FROM teams WHERE team_id = ?", row -> row.getLong(1), teamID);
        if (balance == null || balance <= 0) return new Payout(Outcome.OK, 0, 0);
        int amount = (int) Math.min(Integer.MAX_VALUE, balance);
        int tax = Taxes.taxOn(amount, TaxClass.STANDARD);
        long paid = amount - tax;
        Database.update(connection, "UPDATE teams SET treasury = treasury - ? WHERE team_id = ?", amount, teamID);
        Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", paid, player);
        Treasury.deposit(connection, Treasury.Source.WITHDRAW_TAX, tax, player);
        BankLog.add(connection, player, BankLog.TEAM_WITHDRAW, paid, null);
        return new Payout(Outcome.OK, paid, tax);
    }

    /** After the commit of payOutAll: the state treasury's cached balance. */
    public static void committed(long tax) {
        Treasury.committed(tax);
    }

    /** Inside a transaction: pays a team expense (the team warp); false if the treasury does not cover it. */
    public static boolean pay(@NotNull Connection connection, @NotNull String teamID, @NotNull UUID actor, @NotNull String kind, long amount)
            throws SQLException {
        if (Database.update(connection, "UPDATE teams SET treasury = treasury - ? WHERE team_id = ? AND treasury >= ?", amount, teamID, amount) == 0) {
            return false;
        }
        book(connection, teamID, actor, kind, -amount);
        return true;
    }

    /** The last bookings, newest first. */
    public static List<Entry> history(@NotNull String teamID, int limit) {
        return Database.query("""
                SELECT l.created_at, m.nickname, l.kind, l.amount FROM team_ledger l
                LEFT JOIN team_members m ON m.team_id = l.team_id AND m.uuid = l.player_uuid
                WHERE l.team_id = ? ORDER BY l.created_at DESC, l.id DESC LIMIT ?""",
                row -> new Entry(row.getTimestamp(1).toInstant(), row.getString(2), row.getString(3), row.getLong(4)), teamID, limit);
    }

    // the member's role, null if the player is not in the team
    private static @Nullable String role(Connection connection, String teamID, UUID player) throws SQLException {
        return Database.queryOne(connection, "SELECT role FROM team_members WHERE team_id = ? AND uuid = ?", row -> row.getString(1), teamID, player);
    }

    private static void book(Connection connection, String teamID, UUID player, String kind, long amount) throws SQLException {
        Database.update(connection, "INSERT INTO team_ledger (team_id, player_uuid, kind, amount) VALUES (?, ?, ?, ?)", teamID, player, kind, amount);
    }

}
