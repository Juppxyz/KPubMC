package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.TabListUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The state treasury: every tax is booked into treasury_ledger in the same transaction that takes it from the player.
 * The balance is the sum of the ledger and is cached for the tab list and the shop.
 */
public final class Treasury {

    private Treasury() {}

    public enum Source {
        TRADE_TAX("Handelssteuer"),
        WITHDRAW_TAX("Abhebesteuer"),
        // cash paid in at Basil
        DEPOSIT_TAX("Einzahlsteuer"),
        DEATH_TAX("Todessteuer"),
        NETHER_TAX("Transfersteuer"),
        VAULT_FEE("Schließfach"),
        // Basil's fixed deposits: in when they start, out when they end (owed to the players, no income)
        FIXED_DEPOSIT("Festgeld"),
        // Basil's loans: the treasury's share of the interest, and what a defaulted loan pays beyond the borrowed amount
        LOAN_INTEREST("Kreditzinsen"),
        LOAN_PENALTY("Kreditstrafe"),
        // while the state is broke: the emergency sale (the whole price), bonds sold (owed) and bonds paid back
        EMERGENCY_SALE("Notverkauf"),
        BOND_SALE("Staatsanleihen"),
        BOND_REPAY("Anleihen zurückgezahlt"),
        // paid out: interest on Basil's fixed deposits
        INTEREST("Zinsen"),
        // paid out: the Bauamt's grants for copies of its building templates
        BUILDING_GRANT("Bauförderung"),
        // what players pay for damaging state property (paid builds)
        DAMAGE_FINE("Schadensersatz"),
        // Basil lends the treasury's money: out when a loan starts, back when it is repaid (no income, no spending)
        LOAN_OUT("Kredite ausgezahlt"),
        LOAN_BACK("Kredite zurück"),
        // paid out: the Sheriff's bounties
        BOUNTY("Kopfgeld"),
        // warps, teleports and team foundings
        FEES("Gebühren"),
        // the state's money at the start of a season (no income: it does not raise the Bauamt's budget)
        START_CAPITAL("Startkapital");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final AtomicLong balance = new AtomicLong();
    private static final AtomicBoolean tabRefreshPending = new AtomicBoolean();

    /**
     * Blocking, called in onEnable (after a season reset could have run): a new season (an empty ledger) starts with
     * the start capital from the config, then the balance is summed.
     */
    public static void load() {
        int capital = ConfigManager.getManager().getTreasuryStartCapital();
        if (capital > 0) {
            // only into an empty ledger: the first booking of the season, never a second time
            int booked = Database.update("INSERT INTO treasury_ledger (source, amount) SELECT ?, ? "
                    + "WHERE NOT EXISTS (SELECT 1 FROM treasury_ledger)", Source.START_CAPITAL.name(), capital);
            if (booked > 0) Main.getInstance().getSLF4JLogger().info("new season: treasury starts with {} Schilling", capital);
        }
        Long sum = Database.queryOne("SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger", row -> row.getLong(1));
        balance.set(sum == null ? 0 : sum);
    }

    public static long balance() {
        return balance.get();
    }

    /** Books the amount inside the caller's transaction; call {@link #committed(long)} after the commit. */
    public static void deposit(Connection connection, Source source, long amount, @Nullable UUID player) throws SQLException {
        if (amount <= 0) return;
        Database.update(connection, "INSERT INTO treasury_ledger (source, amount, player_uuid) VALUES (?, ?, ?)",
                source.name(), amount, player);
    }

    /** Pays out of the treasury inside the caller's transaction (a negative entry); call {@link #committed(long)} with -amount. */
    public static void withdraw(Connection connection, Source source, long amount, @Nullable UUID player) throws SQLException {
        if (amount <= 0) return;
        Database.update(connection, "INSERT INTO treasury_ledger (source, amount, player_uuid) VALUES (?, ?, ?)",
                source.name(), -amount, player);
    }

    // after the commit of a booking; negative for refunds and grants
    public static void committed(long amount) {
        if (amount == 0) return;
        balance.addAndGet(amount);
        // the tab list shows the balance: redraw it on the next tick, bookings in the same tick share one redraw
        if (tabRefreshPending.compareAndSet(false, true)) {
            MainThread.run(() -> {
                tabRefreshPending.set(false);
                TabListUtil.updateTabForAll();
            });
        }
        // broke or recovered right after the booking that crossed the line, not only at the next market tick
        Market.requestStateCheck();
    }

    /** What the state took in: every source except the money it owes or paid out (deposits, bonds, interest, grants) and the start capital. */
    public static long income(Map<Source, Long> inflow) {
        return inflow.entrySet().stream()
                .filter(entry -> switch (entry.getKey()) {
                    case INTEREST, FIXED_DEPOSIT, BOND_SALE, BOND_REPAY, BUILDING_GRANT, LOAN_OUT, LOAN_BACK, BOUNTY, START_CAPITAL -> false;
                    default -> true;
                })
                .mapToLong(Map.Entry::getValue).sum();
    }

    /** Blocking: sum of the inflows since the given time per source. */
    public static Map<Source, Long> inflowSince(Instant since) {
        Map<Source, Long> result = new EnumMap<>(Source.class);
        Database.query("SELECT source, SUM(amount) FROM treasury_ledger WHERE created_at >= ? GROUP BY source", row -> {
            try {
                result.put(Source.valueOf(row.getString(1)), row.getLong(2));
            } catch (IllegalArgumentException ignored) {
                // a source written by a newer version
            }
            return null;
        }, Timestamp.from(since));
        return result;
    }

}
