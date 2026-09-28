package xyz.jupp.minecraft.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.Nullable;
import org.postgresql.ds.PGSimpleDataSource;
import xyz.jupp.minecraft.config.ConfigManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL connection pool plus small JDBC helpers. Every method is blocking: call it off the main thread.
 * SQL errors are rethrown as {@link DatabaseException}.
 */
public final class Database {

    public static final String URL_ENV = "KPUB_DATABASE_URL";
    public static final String URL_CONFIG_KEY = "databaseUrl";

    // idempotent, runs on every start; money and points can never become negative
    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS players (
                uuid         UUID PRIMARY KEY,
                money        INTEGER NOT NULL DEFAULT 250 CHECK (money >= 0),
                team_id      TEXT,
                team_invites BOOLEAN NOT NULL DEFAULT FALSE,
                jail         BOOLEAN NOT NULL DEFAULT FALSE,
                jail_end     BIGINT  NOT NULL DEFAULT 0,
                is_wanted    BOOLEAN NOT NULL DEFAULT FALSE,
                created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            """
            CREATE TABLE IF NOT EXISTS teams (
                team_id         TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                color           TEXT,
                owner_uuid      UUID NOT NULL REFERENCES players (uuid),
                points          INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
                level           INTEGER NOT NULL DEFAULT 1,
                zone_pvp        BOOLEAN NOT NULL DEFAULT TRUE,
                zone_mob_damage BOOLEAN NOT NULL DEFAULT TRUE,
                zone_interact   BOOLEAN NOT NULL DEFAULT TRUE,
                created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            """
            CREATE TABLE IF NOT EXISTS team_members (
                team_id  TEXT NOT NULL REFERENCES teams (team_id) ON DELETE CASCADE,
                uuid     UUID NOT NULL REFERENCES players (uuid),
                role     TEXT NOT NULL CHECK (role IN ('owner', 'vice', 'member')),
                nickname TEXT NOT NULL,
                PRIMARY KEY (team_id, uuid)
            )""",
            """
            CREATE TABLE IF NOT EXISTS chunks (
                world   TEXT    NOT NULL,
                x       INTEGER NOT NULL,
                z       INTEGER NOT NULL,
                team_id TEXT    NOT NULL REFERENCES teams (team_id) ON DELETE CASCADE,
                PRIMARY KEY (world, x, z)
            )""",
            """
            CREATE TABLE IF NOT EXISTS warps (
                uuid  UUID PRIMARY KEY REFERENCES players (uuid) ON DELETE CASCADE,
                world TEXT NOT NULL,
                x     DOUBLE PRECISION NOT NULL,
                y     DOUBLE PRECISION NOT NULL,
                z     DOUBLE PRECISION NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS command_log (
                id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                player_uuid UUID NOT NULL,
                player_name TEXT NOT NULL,
                command     TEXT NOT NULL,
                created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            "CREATE INDEX IF NOT EXISTS command_log_player_idx ON command_log (player_uuid, created_at)",
            // economy: catalog with the current demand, daily offers, trades and the state treasury
            """
            CREATE TABLE IF NOT EXISTS market_items (
                material        TEXT PRIMARY KEY,
                category        TEXT NOT NULL,
                display_name    TEXT,
                description     TEXT,
                amount          INTEGER NOT NULL DEFAULT 1 CHECK (amount BETWEEN 1 AND 64),
                base_price      INTEGER NOT NULL CHECK (base_price > 0),
                min_price       INTEGER CHECK (min_price > 0),
                max_price       INTEGER CHECK (max_price > 0),
                elasticity      DOUBLE PRECISION NOT NULL DEFAULT 0.02 CHECK (elasticity >= 0),
                sell_ratio      DOUBLE PRECISION NOT NULL DEFAULT 0.5 CHECK (sell_ratio >= 0 AND sell_ratio <= 0.9),
                buyable         BOOLEAN NOT NULL DEFAULT TRUE,
                sellable        BOOLEAN NOT NULL DEFAULT FALSE,
                core            BOOLEAN NOT NULL DEFAULT TRUE,
                rotation_weight INTEGER NOT NULL DEFAULT 1 CHECK (rotation_weight >= 0),
                enabled         BOOLEAN NOT NULL DEFAULT TRUE,
                demand          DOUBLE PRECISION NOT NULL DEFAULT 0,
                updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            """
            CREATE TABLE IF NOT EXISTS market_rotation (
                day      DATE    NOT NULL,
                slot     INTEGER NOT NULL,
                material TEXT    NOT NULL REFERENCES market_items (material) ON DELETE CASCADE,
                PRIMARY KEY (day, slot)
            )""",
            """
            CREATE TABLE IF NOT EXISTS market_transactions (
                id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                player_uuid UUID    NOT NULL,
                material    TEXT    NOT NULL,
                kind        TEXT    NOT NULL CHECK (kind IN ('BUY', 'SELL')),
                quantity    INTEGER NOT NULL,
                net         INTEGER NOT NULL,
                tax         INTEGER NOT NULL DEFAULT 0,
                created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            "CREATE INDEX IF NOT EXISTS market_transactions_material_idx ON market_transactions (material, created_at)",
            """
            CREATE TABLE IF NOT EXISTS treasury_ledger (
                id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                source      TEXT   NOT NULL,
                amount      BIGINT NOT NULL,
                player_uuid UUID,
                created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            "CREATE INDEX IF NOT EXISTS treasury_ledger_created_idx ON treasury_ledger (created_at)",
            // tax classes, activity for the economy measurement, daily snapshots and the price adjustment log
            "ALTER TABLE market_items ADD COLUMN IF NOT EXISTS tax_class TEXT",
            "ALTER TABLE players ADD COLUMN IF NOT EXISTS last_seen TIMESTAMPTZ",
            """
            CREATE TABLE IF NOT EXISTS economy_snapshots (
                day            DATE PRIMARY KEY,
                money_supply   BIGINT  NOT NULL,
                active_players INTEGER NOT NULL,
                treasury       BIGINT  NOT NULL,
                trade_volume   BIGINT  NOT NULL,
                factor         DOUBLE PRECISION NOT NULL,
                note           TEXT    NOT NULL,
                ai_summary     TEXT,
                created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            """
            CREATE TABLE IF NOT EXISTS market_adjustments (
                id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                day        DATE NOT NULL,
                material   TEXT NOT NULL,
                field      TEXT NOT NULL,
                old_value  TEXT,
                new_value  TEXT,
                reason     TEXT NOT NULL,
                source     TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            "CREATE TABLE IF NOT EXISTS market_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            // effects and services ("Effekte & Dienste" tab), priced like the goods
            """
            CREATE TABLE IF NOT EXISTS market_services (
                key              TEXT PRIMARY KEY,
                kind             TEXT    NOT NULL CHECK (kind IN ('EFFECT', 'REPAIR', 'WEATHER', 'DAY')),
                display_name     TEXT    NOT NULL,
                icon             TEXT    NOT NULL,
                base_price       INTEGER NOT NULL CHECK (base_price > 0),
                elasticity       DOUBLE PRECISION NOT NULL DEFAULT 0.05 CHECK (elasticity >= 0),
                tax_class        TEXT    NOT NULL DEFAULT 'STANDARD',
                effect           TEXT,
                amplifier        INTEGER NOT NULL DEFAULT 0,
                duration_seconds INTEGER NOT NULL DEFAULT 0,
                max_seconds      INTEGER NOT NULL DEFAULT 0,
                sort             INTEGER NOT NULL DEFAULT 0,
                enabled          BOOLEAN NOT NULL DEFAULT TRUE,
                demand           DOUBLE PRECISION NOT NULL DEFAULT 0
            )""",
            // Nomad: team contracts, the open redemption list, hot items of the day and the weekly race
            """
            CREATE TABLE IF NOT EXISTS nomad_contracts (
                id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                slot       INTEGER NOT NULL,
                material   TEXT    NOT NULL,
                required   INTEGER NOT NULL CHECK (required > 0),
                reward     INTEGER NOT NULL CHECK (reward > 0),
                ends_at    TIMESTAMPTZ NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            "CREATE INDEX IF NOT EXISTS nomad_contracts_ends_idx ON nomad_contracts (ends_at)",
            """
            CREATE TABLE IF NOT EXISTS nomad_progress (
                contract_id BIGINT  NOT NULL REFERENCES nomad_contracts (id) ON DELETE CASCADE,
                team_id     TEXT    NOT NULL REFERENCES teams (team_id) ON DELETE CASCADE,
                delivered   INTEGER NOT NULL DEFAULT 0,
                completed   BOOLEAN NOT NULL DEFAULT FALSE,
                PRIMARY KEY (contract_id, team_id)
            )""",
            """
            CREATE TABLE IF NOT EXISTS nomad_redeemables (
                material TEXT PRIMARY KEY,
                points   INTEGER NOT NULL CHECK (points > 0),
                enabled  BOOLEAN NOT NULL DEFAULT TRUE
            )""",
            "CREATE TABLE IF NOT EXISTS nomad_hot (day DATE NOT NULL, material TEXT NOT NULL, PRIMARY KEY (day, material))",
            """
            CREATE TABLE IF NOT EXISTS nomad_deliveries (
                id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                team_id     TEXT    NOT NULL,
                player_uuid UUID,
                kind        TEXT    NOT NULL CHECK (kind IN ('CONTRACT', 'REDEEM', 'RACE')),
                material    TEXT,
                quantity    INTEGER NOT NULL DEFAULT 0,
                points      INTEGER NOT NULL,
                created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            "CREATE INDEX IF NOT EXISTS nomad_deliveries_created_idx ON nomad_deliveries (created_at)",
            "CREATE TABLE IF NOT EXISTS nomad_race_payouts (week TEXT PRIMARY KEY, paid_at TIMESTAMPTZ NOT NULL DEFAULT now())",
            // Hondo: friendship per player, every trade with him and the friendship offers already bought
            """
            CREATE TABLE IF NOT EXISTS hondo_friends (
                player_uuid   UUID PRIMARY KEY,
                points        DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (points >= 0),
                trades        INTEGER NOT NULL DEFAULT 0,
                volume        BIGINT  NOT NULL DEFAULT 0,
                last_trade_at TIMESTAMPTZ
            )""",
            """
            CREATE TABLE IF NOT EXISTS hondo_trades (
                id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                player_uuid    UUID    NOT NULL,
                kind           TEXT    NOT NULL CHECK (kind IN ('BUY', 'EXCHANGE', 'OFFER')),
                material       TEXT    NOT NULL,
                quantity       INTEGER NOT NULL,
                given_material TEXT,
                given_quantity INTEGER NOT NULL DEFAULT 0,
                value          INTEGER NOT NULL,
                points         DOUBLE PRECISION NOT NULL,
                created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            "CREATE INDEX IF NOT EXISTS hondo_trades_player_idx ON hondo_trades (player_uuid, created_at)",
            "CREATE INDEX IF NOT EXISTS hondo_trades_created_idx ON hondo_trades (created_at)",
            """
            CREATE TABLE IF NOT EXISTS hondo_claims (
                player_uuid UUID    NOT NULL,
                level       INTEGER NOT NULL,
                claimed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (player_uuid, level)
            )""",
            """
            CREATE TABLE IF NOT EXISTS hondo_pending (
                id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                player_uuid UUID    NOT NULL,
                material    TEXT    NOT NULL,
                quantity    INTEGER NOT NULL CHECK (quantity > 0),
                created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            "CREATE INDEX IF NOT EXISTS hondo_pending_player_idx ON hondo_pending (player_uuid)",
            // Hondo's exchanges are logged as trades but move no money (the statement skips them)
            "ALTER TABLE market_transactions ADD COLUMN IF NOT EXISTS moves_money BOOLEAN NOT NULL DEFAULT TRUE",
            // Basil's bank: statement bookings, fixed deposits and the vaults
            """
            CREATE TABLE IF NOT EXISTS bank_log (
                id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                player_uuid UUID   NOT NULL,
                kind        TEXT   NOT NULL,
                amount      BIGINT NOT NULL,
                note        TEXT,
                created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""",
            "CREATE INDEX IF NOT EXISTS bank_log_player_idx ON bank_log (player_uuid, created_at)",
            """
            CREATE TABLE IF NOT EXISTS bank_deposits (
                id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                player_uuid UUID    NOT NULL,
                amount      INTEGER NOT NULL CHECK (amount > 0),
                rate        DOUBLE PRECISION NOT NULL,
                starts_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                ends_at     TIMESTAMPTZ NOT NULL,
                paid_out    BOOLEAN NOT NULL DEFAULT FALSE,
                early       BOOLEAN NOT NULL DEFAULT FALSE,
                interest    INTEGER NOT NULL DEFAULT 0,
                closed_at   TIMESTAMPTZ
            )""",
            "CREATE INDEX IF NOT EXISTS bank_deposits_open_idx ON bank_deposits (player_uuid) WHERE NOT paid_out",
            // the deposited amount lies in the treasury (deposits from before were booked in by Bank.load)
            "ALTER TABLE bank_deposits ADD COLUMN IF NOT EXISTS in_treasury BOOLEAN NOT NULL DEFAULT FALSE",
            """
            CREATE TABLE IF NOT EXISTS bank_loans (
                id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                player_uuid    UUID    NOT NULL,
                principal      INTEGER NOT NULL CHECK (principal > 0),
                rate           DOUBLE PRECISION NOT NULL,
                treasury_share DOUBLE PRECISION NOT NULL,
                locked         INTEGER NOT NULL,
                state          TEXT    NOT NULL CHECK (state IN ('OPEN', 'REPAID', 'DEFAULTED', 'SETTLED')),
                paid           BIGINT  NOT NULL DEFAULT 0,
                taken_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                due_at         TIMESTAMPTZ NOT NULL,
                closed_at      TIMESTAMPTZ
            )""",
            "CREATE UNIQUE INDEX IF NOT EXISTS bank_loans_active_idx ON bank_loans (player_uuid) WHERE state IN ('OPEN', 'DEFAULTED')",
            "CREATE INDEX IF NOT EXISTS bank_loans_due_idx ON bank_loans (due_at) WHERE state = 'OPEN'",
            """
            CREATE TABLE IF NOT EXISTS bank_vaults (
                player_uuid UUID  PRIMARY KEY,
                items       BYTEA NOT NULL,
                updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )"""
    );

    private static volatile HikariDataSource dataSource;

    private Database() {}


    /* lifecycle: connect() in onEnable after the config was loaded, close() in onDisable */

    public static synchronized void connect() {
        if (dataSource != null) return;

        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setURL(resolveUrl());

        HikariConfig config = new HikariConfig();
        config.setPoolName("KPubMC");
        config.setDataSource(postgres);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5_000);

        HikariDataSource pool;
        try {
            pool = new HikariDataSource(config);
        } catch (RuntimeException e) {
            throw new IllegalStateException("PostgreSQL nicht erreichbar oder Anmeldung fehlgeschlagen (" + URL_ENV
                    + " / " + URL_CONFIG_KEY + " prüfen): " + e.getMessage(), e);
        }

        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : SCHEMA) statement.execute(sql);
        } catch (SQLException e) {
            pool.close();
            throw new IllegalStateException("Datenbankschema konnte nicht angelegt werden: " + e.getMessage(), e);
        }
        dataSource = pool;
    }

    public static synchronized void close() {
        if (dataSource == null) return;
        dataSource.close();
        dataSource = null;
    }

    private static String resolveUrl() {
        String fromEnvironment = System.getenv(URL_ENV);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment.trim();

        String fromConfig = ConfigManager.getManager().getDatabaseUrl();
        if (fromConfig != null && !fromConfig.isBlank()) return fromConfig.trim();

        throw new IllegalStateException("Keine Datenbank konfiguriert: Umgebungsvariable " + URL_ENV
                + " setzen oder \"" + URL_CONFIG_KEY + "\" in plugins/kpub/config.json eintragen,"
                + " z. B. jdbc:postgresql://localhost:5432/kpubmc?user=kpubmc&password=...");
    }


    /* JDBC helpers */

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    public static <T> T withConnection(SqlWork<T> work) {
        HikariDataSource pool = dataSource;
        if (pool == null) throw new DatabaseException("Database is not connected", null);
        try (Connection connection = pool.getConnection()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw new DatabaseException(e.getMessage(), e);
        }
    }

    /** Runs the work in one transaction: committed if it returns, rolled back if it throws. */
    public static <T> T inTransaction(SqlWork<T> work) {
        return withConnection(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public static int update(String sql, Object... params) {
        return withConnection(connection -> update(connection, sql, params));
    }

    public static int update(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, params)) {
            return statement.executeUpdate();
        }
    }

    public static <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(connection -> query(connection, sql, mapper, params));
    }

    public static <T> List<T> query(Connection connection, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, params); ResultSet rows = statement.executeQuery()) {
            List<T> result = new ArrayList<>();
            while (rows.next()) result.add(mapper.map(rows));
            return result;
        }
    }

    public static <T> @Nullable T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(connection -> queryOne(connection, sql, mapper, params));
    }

    public static <T> @Nullable T queryOne(Connection connection, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> rows = query(connection, sql, mapper, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) {
                statement.setNull(i + 1, Types.NULL);
            } else {
                statement.setObject(i + 1, params[i]);
            }
        }
        return statement;
    }

}
