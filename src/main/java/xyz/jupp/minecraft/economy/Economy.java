package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Locale;

/**
 * The economy automatic: once a day the money supply per active player is measured and turned into a factor that
 * scales every tax rate. Growth above the target (e.g. farm money flooding in) raises the factor, a shrinking economy
 * or a full treasury lowers it. The factor moves at most {@link #MAX_DAILY_STEP} per day and stays within the configured
 * bounds.
 */
public final class Economy {

    private Economy() {}

    static final double MAX_DAILY_STEP = 0.05;
    // a treasury above its target lowers the taxes by this much per day
    private static final double TREASURY_STEP = 0.02;
    private static final int ACTIVE_DAYS = 7;

    public record Snapshot(LocalDate day, long moneySupply, int activePlayers, long treasury, long tradeVolume,
                           double factor, String note) {
        public long perPlayer() {
            return moneySupply / Math.max(1, activePlayers);
        }
    }

    private static volatile double factor = 1.0;
    private static volatile double previousFactor = 1.0;
    private static volatile String note = "Noch keine Messung";
    private static volatile @Nullable LocalDate lastDay = null;


    /** Blocking, called in onEnable: restores the factor of the latest measurement. */
    public static void load() {
        Snapshot latest = latestBefore(LocalDate.MAX);
        if (latest == null) return;
        Snapshot before = latestBefore(latest.day());
        factor = latest.factor();
        previousFactor = before == null ? latest.factor() : before.factor();
        note = latest.note();
        lastDay = latest.day();
    }

    public static double factor() {
        return factor;
    }

    /** ▲, ▼ or ● compared with the previous day. */
    public static String trendSymbol() {
        if (factor > previousFactor + 1e-9) return "▲";
        if (factor < previousFactor - 1e-9) return "▼";
        return "●";
    }

    public static String note() {
        return note;
    }

    /** The tax level in plain words for players: gesenkt, normal or erhöht (with colour code). */
    public static String levelWord() {
        if (factor <= 0.95) return "§agesenkt";
        if (factor >= 1.05) return "§cerhöht";
        return "§fnormal";
    }

    public static boolean isMeasured(@NotNull LocalDate day) {
        return day.equals(lastDay);
    }

    /** Blocking: measures the economy for the day, stores the snapshot and applies the new factor. */
    public static Snapshot measure(@NotNull LocalDate day) {
        long moneySupply = Database.queryOne("SELECT COALESCE(SUM(money), 0) FROM players", row -> row.getLong(1));
        Integer active = Database.queryOne("SELECT COUNT(*) FROM players WHERE last_seen >= now() - make_interval(days => ?)",
                row -> row.getInt(1), ACTIVE_DAYS);
        Long volume = Database.queryOne("SELECT COALESCE(SUM(net), 0) FROM market_transactions WHERE created_at >= now() - interval '1 day'",
                row -> row.getLong(1));
        int activePlayers = Math.max(1, active == null ? 0 : active);
        long treasury = Treasury.balance();

        Snapshot previous = latestBefore(day);
        ConfigManager config = ConfigManager.getManager();
        Step step = nextFactor(previous, moneySupply / activePlayers, treasury / activePlayers, config.getEconomyTargetGrowth(),
                config.getTreasuryTargetPerPlayer(), config.getEconomyFactorMin(), config.getEconomyFactorMax());

        Snapshot snapshot = new Snapshot(day, moneySupply, activePlayers, treasury, volume == null ? 0 : volume, step.factor(), step.note());
        Database.update("""
                INSERT INTO economy_snapshots (day, money_supply, active_players, treasury, trade_volume, factor, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (day) DO UPDATE SET money_supply = EXCLUDED.money_supply, active_players = EXCLUDED.active_players,
                    treasury = EXCLUDED.treasury, trade_volume = EXCLUDED.trade_volume, factor = EXCLUDED.factor, note = EXCLUDED.note""",
                Date.valueOf(day), moneySupply, activePlayers, treasury, snapshot.tradeVolume(), step.factor(), step.note());

        previousFactor = previous == null ? step.factor() : previous.factor();
        factor = step.factor();
        note = step.note();
        lastDay = day;
        Main.getInstance().getSLF4JLogger().info("economy {}: money {} ({} per active player), treasury {}, factor {} ({})",
                day, moneySupply, snapshot.perPlayer(), treasury, String.format(Locale.ROOT, "%.2f", step.factor()), step.note());
        return snapshot;
    }

    public record Step(double factor, String note) {}

    /** The controller itself, without database access. */
    static Step nextFactor(@Nullable Snapshot previous, long perPlayer, long treasuryPerPlayer, double targetGrowth,
                           int treasuryTarget, double min, double max) {
        if (previous == null || previous.perPlayer() <= 0) {
            return new Step(clamp(1.0, min, max), "Erste Messung, Steuern normal");
        }
        double growth = (double) perPlayer / previous.perPlayer() - 1.0;
        double step = clamp(growth - targetGrowth, -MAX_DAILY_STEP, MAX_DAILY_STEP);
        String reason = String.format(Locale.GERMANY, "Geldmenge pro Spieler %+.1f %% (Ziel %+.1f %%)", growth * 100, targetGrowth * 100);
        if (treasuryTarget > 0 && treasuryPerPlayer > treasuryTarget) {
            step -= TREASURY_STEP;
            reason += ", Staatskasse gut gefüllt";
        }
        double next = clamp(previous.factor() + step, min, max);
        String direction = next > previous.factor() + 1e-9 ? "Steuern steigen"
                : next < previous.factor() - 1e-9 ? "Steuern sinken" : "Steuern stabil";
        return new Step(next, reason + ": " + direction);
    }

    private static @Nullable Snapshot latestBefore(LocalDate day) {
        return Database.queryOne("SELECT * FROM economy_snapshots WHERE day < ? ORDER BY day DESC LIMIT 1", row -> new Snapshot(
                row.getDate("day").toLocalDate(), row.getLong("money_supply"), row.getInt("active_players"),
                row.getLong("treasury"), row.getLong("trade_volume"), row.getDouble("factor"), row.getString("note")),
                Date.valueOf(day.equals(LocalDate.MAX) ? LocalDate.of(9999, 12, 31) : day));
    }

    /** Blocking: stores the summary of the AI review for the day. */
    static void saveAiSummary(@NotNull LocalDate day, @NotNull String summary) {
        Database.update("UPDATE economy_snapshots SET ai_summary = ? WHERE day = ?", summary, Date.valueOf(day));
    }

    /** Blocking: the AI summary of the latest measured day, if any. */
    public static @Nullable String latestAiSummary() {
        return Database.queryOne("SELECT ai_summary FROM economy_snapshots WHERE ai_summary IS NOT NULL ORDER BY day DESC LIMIT 1",
                row -> row.getString(1));
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

}
