package xyz.jupp.minecraft.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.Market;

import java.sql.SQLException;
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
 * Every table of the plugin's schema must be in {@link #WIPE} or {@link #KEEP}; a table in neither (a new one nobody
 * sorted in yet) stops the reset, it would otherwise survive the season by accident. Tables of other software in the
 * same database are neither copied nor touched. Why a reset failed is shown by /saisonreset until the next try.
 */
public final class SeasonReset {

    private SeasonReset() {}

    static final String PENDING_KEY = "season_reset_pending";
    // why the last try at a start failed, shown by /saisonreset; removed by a successful reset, a new mark or a cancel
    static final String ERROR_KEY = "season_reset_error";
    // the number of the season, counted up by every reset (kept, not in SEASON_META): cash of an older season is void
    static final String SEASON_KEY = "season";
    // the day the current season began (written by the reset): the market report knows a new season started
    static final String STARTED_KEY = "season_started";

    // the day the season began, cached in onEnable (loadStart), read on any thread
    private static volatile @Nullable java.time.LocalDate startDay;

    /**
     * Blocking, in onEnable after the treasury was loaded: the day the season began. A reset stored it; otherwise the
     * treasury's first booking (a new database: the start capital), otherwise today; that day is stored so it stays.
     */
    public static void loadStart() {
        java.time.LocalDate day = started();
        if (day == null) {
            java.sql.Timestamp first = Database.queryOne("SELECT MIN(created_at) FROM treasury_ledger", row -> row.getTimestamp(1));
            day = first == null ? java.time.LocalDate.now(Market.ZONE) : first.toInstant().atZone(Market.ZONE).toLocalDate();
            Database.update("INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING", STARTED_KEY, day.toString());
        }
        startDay = day;
    }

    /**
     * Blocking, admins (/wirtschaft): the season begins today, e.g. on the opening day of a server that ran a while for
     * the setup before (a fresh database counts the season from its first start).
     */
    public static void startToday(@NotNull String by) {
        java.time.LocalDate today = java.time.LocalDate.now(Market.ZONE);
        Database.update("INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                STARTED_KEY, today.toString());
        startDay = today;
        Main.getInstance().getSLF4JLogger().warn("season start set to {} by {}", today, by);
    }

    /** The day the season began (today until loadStart ran). */
    public static java.time.LocalDate startDay() {
        java.time.LocalDate day = startDay;
        return day == null ? java.time.LocalDate.now(Market.ZONE) : day;
    }

    /** The day of the season: 1 on the day it began. */
    public static long seasonDay() {
        return java.time.temporal.ChronoUnit.DAYS.between(startDay(), java.time.LocalDate.now(Market.ZONE)) + 1;
    }

    /** The day the current season began as stored, null if none is stored. */
    public static @Nullable java.time.LocalDate started() {
        String value = Database.queryOne("SELECT value FROM market_meta WHERE key = ?", row -> row.getString(1), STARTED_KEY);
        try {
            return value == null ? null : java.time.LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

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
            PENDING_KEY, ERROR_KEY,
            // the admins' netherite and loan decisions belong to the season (AdminSettings)
            "setting.netherite_mode", "setting.loans_open");

    /** Who marked the reset and when, null if none is marked. */
    public static @Nullable String pending() {
        return Database.queryOne("SELECT value FROM market_meta WHERE key = ?", row -> row.getString(1), PENDING_KEY);
    }

    /** Why the last try failed (time and reason), null if it did not fail. */
    public static @Nullable String lastError() {
        return Database.queryOne("SELECT value FROM market_meta WHERE key = ?", row -> row.getString(1), ERROR_KEY);
    }

    public static void mark(@NotNull String by) {
        String value = LocalDateTime.now(Market.ZONE).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + " von " + by;
        Database.update("INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                PENDING_KEY, value);
        Database.update("DELETE FROM market_meta WHERE key = ?", ERROR_KEY);
        Main.getInstance().getSLF4JLogger().warn("season reset marked for the next start ({})", value);
    }

    /** True if a mark was removed. */
    public static boolean cancel() {
        Database.update("DELETE FROM market_meta WHERE key = ?", ERROR_KEY);
        return Database.update("DELETE FROM market_meta WHERE key = ?", PENDING_KEY) > 0;
    }

    /**
     * Blocking, in onEnable right after Database.connect and before anything loads: runs a marked reset. Returns where
     * the backup is, null if nothing was marked or the reset failed (then nothing changed, see the log and /saisonreset).
     */
    public static @Nullable String runIfPending() {
        String marked = pending();
        if (marked == null) return null;
        String backup = "backup_" + LocalDateTime.now(Market.ZONE).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Main.getInstance().getSLF4JLogger().warn("SEASON RESET starting (marked {})", marked);
        String location;
        try {
            location = Database.inTransaction(connection -> {
                // copying and emptying big tables may take longer than a normal statement (the connection goes back to
                // the pool with its defaults, the SET LOCAL values end with the transaction)
                connection.setNetworkTimeout(Runnable::run, 0);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout = 0");
                    // a lock held elsewhere (a database tool with an open transaction) must not hang the start forever:
                    // after a minute the reset fails, nothing changes and /saisonreset says why
                    statement.execute("SET LOCAL lock_timeout = '60s'");
                }
                // the plugin's own tables only: other software may keep tables in the same database
                Set<String> known = new HashSet<>(WIPE);
                known.addAll(KEEP);
                List<String> unsorted = Database.tables().stream().filter(table -> !known.contains(table)).toList();
                if (!unsorted.isEmpty()) throw new IllegalStateException("Tabellen ohne Einordnung (SeasonReset.WIPE/KEEP): " + unsorted);
                Set<String> present = new HashSet<>(Database.query(connection, "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'", row -> row.getString(1)));
                List<String> missing = WIPE.stream().filter(table -> !present.contains(table)).toList();
                if (!missing.isEmpty()) throw new IllegalStateException("Tabellen fehlen: " + missing);
                List<String> ours = new ArrayList<>(WIPE);
                ours.addAll(KEEP.stream().filter(present::contains).toList());

                // the backup in a schema of its own if the database user may create one ("permission denied for
                // database" otherwise), else next to the tables: the try runs under a savepoint, so a refusal does not
                // abort the transaction
                boolean ownSchema;
                java.sql.Savepoint beforeSchema = connection.setSavepoint();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + backup);
                    ownSchema = true;
                } catch (SQLException e) {
                    connection.rollback(beforeSchema);
                    ownSchema = false;
                    Main.getInstance().getSLF4JLogger().warn("no own schema for the backup ({}), the copies go next to the tables as {}_*",
                            e.getMessage(), backup);
                }
                connection.releaseSavepoint(beforeSchema);
                try (Statement statement = connection.createStatement()) {
                    for (String table : ours) {
                        String copy = ownSchema ? backup + "." + quote(table) : quote(backup + "_" + table);
                        statement.execute("CREATE TABLE " + copy + " AS TABLE " + quote(table));
                    }
                    // no CASCADE: a kept table that still points at an emptied one makes this fail instead of being
                    // emptied along without anyone noticing
                    statement.execute("TRUNCATE " + String.join(", ", WIPE.stream().map(SeasonReset::quote).toList()) + " RESTART IDENTITY");
                    statement.execute("UPDATE market_items SET demand = 0");
                    statement.execute("UPDATE market_services SET demand = 0");
                }
                String placeholders = String.join(", ", java.util.Collections.nCopies(SEASON_META.size(), "?"));
                Database.update(connection, "DELETE FROM market_meta WHERE key IN (" + placeholders + ")", SEASON_META.toArray());
                // the market report of the old season went with economy_snapshots; the next one knows the season is new
                Database.update(connection, "INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                        STARTED_KEY, java.time.LocalDate.now(Market.ZONE).toString());
                // a new season: the bank notes of the old one are worth nothing from now on (Cash)
                Database.update(connection, "INSERT INTO market_meta (key, value) VALUES (?, '1') ON CONFLICT (key) DO UPDATE SET value = "
                        + "(CASE WHEN market_meta.value ~ '^[0-9]+$' THEN market_meta.value::int + 1 ELSE 1 END)::text", SEASON_KEY);
                return ownSchema ? "Schema " + backup : "Tabellen " + backup + "_*";
            });
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().error("SEASON RESET FAILED, nothing was changed, it is tried again at the next start: {}",
                    e.getMessage(), e);
            // outside the rolled back transaction, so /saisonreset can show it
            String reason = String.valueOf(e.getMessage());
            if (reason.length() > 300) reason = reason.substring(0, 300) + " ...";
            try {
                Database.update("INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                        ERROR_KEY, LocalDateTime.now(Market.ZONE).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + ": " + reason);
            } catch (RuntimeException ignored) {
                // the log has it
            }
            return null;
        }
        Main.getInstance().getSLF4JLogger().warn("SEASON RESET done (marked {}): {} tables emptied, backup: {}",
                marked, WIPE.size(), location);
        return location;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

}
