package xyz.jupp.minecraft.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.Market;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The season start: everything the players built up starts over, the admins' setup stays; the world and the
 * inventories are not touched (the setup has world coordinates, the map stays). An admin only marks it
 * ({@code /saisonreset}); it runs at the next server start, right after the schema and before anything is loaded, so
 * no cache holds old data. Every table is copied into a backup schema first, all in one transaction: a failure
 * leaves the data as it was and the mark stays for the next start.
 * <p>
 * Every table of the plugin must be in {@link #WIPE} or {@link #KEEP}; a table in neither (a new one nobody sorted
 * in yet) stops the reset, it would otherwise survive the season by accident.
 */
public final class SeasonReset {

    private SeasonReset() {}

    static final String PENDING_KEY = "season_reset_pending";
    // the number of the season, counted up by every reset (kept, not in SEASON_META): cash of an older season is void
    static final String SEASON_KEY = "season";

    /** The current season, 0 before the first reset. */
    public static int season() {
        String value = Database.queryOne("SELECT value FROM market_meta WHERE key = ?", row -> row.getString(1), SEASON_KEY);
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Emptied: what players built up (money, teams, claims, economy, Bauamt orders and bills, bounties ...). */
    static final List<String> WIPE = List.of(
            "players", "teams", "team_members", "chunks", "warps",
            "team_ledger", "team_warps", "team_relations", "team_requests",
            "market_rotation", "market_transactions", "treasury_ledger", "economy_snapshots", "economy_wealth",
            "random_draws", "emergency_sale", "emergency_purchases", "chest_shop_sales",
            "nomad_contracts", "nomad_progress", "nomad_redeemables", "nomad_hot", "nomad_deliveries", "nomad_race_payouts",
            "hondo_friends", "hondo_trades", "hondo_claims", "hondo_pending",
            "bank_log", "bank_deposits", "bank_loans", "bank_vaults", "state_bonds",
            "bauamt_orders", "bauamt_damage",
            "bounties", "bounty_pledges");

    /**
     * Kept: the admins' setup and logs; the catalog and services only lose their demand. The Bauamt's register of
     * copies stays too: the copies still stand in the world, and an emptied register would let anyone with a new order
     * report an old copy (or the admin's original model) and be paid for it again.
     */
    static final List<String> KEEP = List.of(
            "market_items", "market_services", "market_adjustments", "market_meta", "command_log",
            "bauamt_templates", "bauamt_maps", "bauamt_builds", "jail_cells", "wanted_board", "map_spawn", "map_areas");

    /** market_meta keys that belong to the season; the others (one-time migrations, catalog version) stay. */
    static final List<String> SEASON_META = List.of(
            "state_broke", "state_founded", "demand_decayed_at", "hondo_evaluated_day", "ai_reviewed_day", "ai_review_error",
            PENDING_KEY);

    /** Who marked the reset and when, null if none is marked. */
    public static @Nullable String pending() {
        return Database.queryOne("SELECT value FROM market_meta WHERE key = ?", row -> row.getString(1), PENDING_KEY);
    }

    public static void mark(@NotNull String by) {
        String value = LocalDateTime.now(Market.ZONE).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + " von " + by;
        Database.update("INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                PENDING_KEY, value);
        Main.getInstance().getSLF4JLogger().warn("season reset marked for the next start ({})", value);
    }

    /** True if a mark was removed. */
    public static boolean cancel() {
        return Database.update("DELETE FROM market_meta WHERE key = ?", PENDING_KEY) > 0;
    }

    /**
     * Blocking, in onEnable right after Database.connect and before anything loads: runs a marked reset. Returns the
     * backup schema, null if nothing was marked or the reset failed (then nothing changed, see the log).
     */
    public static @Nullable String runIfPending() {
        String marked = pending();
        if (marked == null) return null;
        String backup = "backup_" + LocalDateTime.now(Market.ZONE).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Main.getInstance().getSLF4JLogger().warn("SEASON RESET starting (marked {}), backup into schema {}", marked, backup);
        try {
            Database.inTransaction(connection -> {
                // copying and emptying big tables may take longer than a normal statement (the connection goes back to
                // the pool with its defaults, the SET LOCAL values end with the transaction)
                connection.setNetworkTimeout(Runnable::run, 0);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout = 0");
                    statement.execute("SET LOCAL lock_timeout = 0");
                }
                List<String> tables = Database.query(connection, "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' ORDER BY table_name", row -> row.getString(1));
                Set<String> known = new HashSet<>(WIPE);
                known.addAll(KEEP);
                List<String> unknown = new ArrayList<>(tables.stream().filter(table -> !known.contains(table)).toList());
                if (!unknown.isEmpty()) {
                    throw new IllegalStateException("tables neither in SeasonReset.WIPE nor KEEP: " + unknown);
                }
                List<String> missing = WIPE.stream().filter(table -> !tables.contains(table)).toList();
                if (!missing.isEmpty()) throw new IllegalStateException("tables to empty do not exist: " + missing);

                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + backup);
                    for (String table : tables) {
                        statement.execute("CREATE TABLE " + backup + "." + quote(table) + " AS TABLE " + quote(table));
                    }
                    // no CASCADE: a kept table that still points at an emptied one makes this fail instead of being
                    // emptied along without anyone noticing
                    statement.execute("TRUNCATE " + String.join(", ", WIPE.stream().map(SeasonReset::quote).toList()) + " RESTART IDENTITY");
                    statement.execute("UPDATE market_items SET demand = 0");
                    statement.execute("UPDATE market_services SET demand = 0");
                }
                String placeholders = String.join(", ", java.util.Collections.nCopies(SEASON_META.size(), "?"));
                Database.update(connection, "DELETE FROM market_meta WHERE key IN (" + placeholders + ")", SEASON_META.toArray());
                // a new season: the bank notes of the old one are worth nothing from now on (Cash)
                Database.update(connection, "INSERT INTO market_meta (key, value) VALUES (?, '1') ON CONFLICT (key) DO UPDATE SET value = "
                        + "(CASE WHEN market_meta.value ~ '^[0-9]+$' THEN market_meta.value::int + 1 ELSE 1 END)::text", SEASON_KEY);
                return null;
            });
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().error("SEASON RESET FAILED, nothing was changed, it is tried again at the next start: {}",
                    e.getMessage(), e);
            return null;
        }
        Main.getInstance().getSLF4JLogger().warn("SEASON RESET done (marked {}): {} tables emptied, backup in schema {}",
                marked, WIPE.size(), backup);
        return backup;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

}
