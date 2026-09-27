package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
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
        DEATH_TAX("Todessteuer"),
        NETHER_TAX("Transfersteuer");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final AtomicLong balance = new AtomicLong();

    /** Blocking, called in onEnable. */
    public static void load() {
        Long sum = Database.queryOne("SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger", row -> row.getLong(1));
        balance.set(sum == null ? 0 : sum);
    }

    public static long balance() {
        return balance.get();
    }

    /** Books the amount inside the caller's transaction; call {@link #committed(long)} after the commit. */
    static void deposit(Connection connection, Source source, long amount, @Nullable UUID player) throws SQLException {
        if (amount <= 0) return;
        Database.update(connection, "INSERT INTO treasury_ledger (source, amount, player_uuid) VALUES (?, ?, ?)",
                source.name(), amount, player);
    }

    // after the commit of a booking; negative for refunds
    static void committed(long amount) {
        if (amount != 0) balance.addAndGet(amount);
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
