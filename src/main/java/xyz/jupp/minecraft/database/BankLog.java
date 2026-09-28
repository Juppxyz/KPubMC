package xyz.jupp.minecraft.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Table bank_log: the account bookings Basil shows in the statement (cash, transfers, fixed deposits, vault), always
 * written in the transaction of the booking itself. Trades and taxes come from their own tables.
 */
public final class BankLog {

    private BankLog() {}

    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAW = "WITHDRAW";
    public static final String TRANSFER_OUT = "TRANSFER_OUT";
    public static final String TRANSFER_IN = "TRANSFER_IN";
    public static final String TERM_START = "TERM_START";
    public static final String TERM_PAYOUT = "TERM_PAYOUT";
    public static final String TERM_CANCEL = "TERM_CANCEL";
    public static final String VAULT_FEE = "VAULT_FEE";
    public static final String LOAN_TAKE = "LOAN_TAKE";
    public static final String LOAN_REPAY = "LOAN_REPAY";
    public static final String LOAN_DEFAULT = "LOAN_DEFAULT";
    public static final String LOAN_APOLOGY = "LOAN_APOLOGY";
    public static final String BOND_BUY = "BOND_BUY";
    public static final String BOND_REPAY = "BOND_REPAY";

    /**
     * SQL for the part of the account an open loan locks (borrowed amount plus its full interest): it may be spent, but
     * not transferred, withdrawn or put into a fixed deposit. One parameter: the player.
     */
    public static final String LOAN_LOCK = "(SELECT COALESCE(SUM(locked), 0) FROM bank_loans WHERE player_uuid = ? AND state = 'OPEN')";

    /** amount: change of the account, negative for money that left it. */
    public static void add(@NotNull Connection connection, @NotNull UUID player, @NotNull String kind, long amount,
                           @Nullable String note) throws SQLException {
        Database.update(connection, "INSERT INTO bank_log (player_uuid, kind, amount, note) VALUES (?, ?, ?, ?)",
                player, kind, amount, note);
    }

}
