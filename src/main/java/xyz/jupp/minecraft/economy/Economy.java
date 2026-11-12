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
 * The economy automatic: once a day the money of the players is measured and turned into a factor that scales every tax
 * rate. Growth above the target (e.g. farm money flooding in) raises the factor, a shrinking economy or a full treasury
 * lowers it. The factor moves at most {@link #MAX_DAILY_STEP} per day and stays within the configured bounds.
 * <p>
 * The growth compares the same holders on both days (the players active in the last 7 days who were measured the day
 * before too, and the team treasuries): a newcomer's start money, a player who stops playing or an alt logging in do
 * not count as growth or shrinking. Borrowed money of open loans is not counted (it disappears again on repayment),
 * running fixed deposits and bonds are (they still belong to the players).
 */
public final class Economy {

    private Economy() {}

    static final double MAX_DAILY_STEP = 0.05;
    // a treasury above its target lowers the taxes by this much per day
    private static final double TREASURY_STEP = 0.02;
    private static final int ACTIVE_DAYS = 7;
    // per-holder measurements are kept this long (only yesterday's are needed)
    private static final int WEALTH_KEEP_DAYS = 30;

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
        // a factor set by the recovery is no daily step: no trend after a restart either
        previousFactor = before == null || RECOVERED_NOTE.equals(latest.note()) ? latest.factor() : before.factor();
        note = latest.note();
        lastDay = latest.day();
    }

    public static double factor() {
        // the state is broke: taxes at the highest factor until it has recovered
        return Bankruptcy.isBroke() ? Math.max(factor, ConfigManager.getManager().getEconomyFactorMax()) : factor;
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

    /** The tax level in plain words for players: gesenkt, normal or erhöht (with colour code), and ▲/▼ while it moves. */
    public static String levelWord() {
        String level = switch (levelPlain()) {
            case "gesenkt" -> "§agesenkt";
            case "erhöht" -> "§cerhöht";
            default -> "§fnormal";
        };
        // while broke the factor is pinned at the maximum, the daily direction says nothing then
        if (Bankruptcy.isBroke()) return level;
        String trend = trendSymbol();
        return "●".equals(trend) ? level : level + " §7" + trend;
    }

    /** gesenkt, normal or erhöht, without colour. */
    public static String levelPlain() {
        double current = factor();
        if (current <= 0.95) return "gesenkt";
        if (current >= 1.05) return "erhöht";
        return "normal";
    }

    /** Where the taxes went at the last measurement: steigen, sinken or bleiben. */
    public static String directionWord() {
        // while broke the taxes stay at the maximum whatever the measurement says
        if (Bankruptcy.isBroke()) return "bleiben";
        return switch (trendSymbol()) {
            case "▲" -> "steigen";
            case "▼" -> "sinken";
            default -> "bleiben";
        };
    }

    public static boolean isMeasured(@NotNull LocalDate day) {
        return day.equals(lastDay);
    }

    /** Blocking: measures the economy for the day, stores the snapshot and applies the new factor. */
    public static synchronized Snapshot measure(@NotNull LocalDate day) {
        // money in running fixed deposits, bonds and team treasuries still belongs to the players, borrowed money not yet
        long moneySupply = Database.queryOne("SELECT COALESCE(SUM(money), 0) + (SELECT COALESCE(SUM(amount), 0) FROM bank_deposits WHERE NOT paid_out)"
                        + " + (SELECT COALESCE(SUM(amount), 0) FROM state_bonds WHERE NOT repaid)"
                        + " + (SELECT COALESCE(SUM(amount), 0) FROM bounty_pledges WHERE paid_at IS NULL AND refunded_at IS NULL)"
                        + " - (SELECT COALESCE(SUM(principal), 0) FROM bank_loans WHERE state = 'OPEN')"
                        + " + (SELECT COALESCE(SUM(treasury), 0) FROM teams) FROM players",
                row -> row.getLong(1));
        Integer active = Database.queryOne("SELECT COUNT(*) FROM players WHERE last_seen >= now() - make_interval(days => ?)",
                row -> row.getInt(1), ACTIVE_DAYS);
        Long volume = Database.queryOne("SELECT COALESCE(SUM(net), 0) FROM market_transactions WHERE created_at >= now() - interval '1 day' AND moves_money",
                row -> row.getLong(1));
        int activePlayers = Math.max(1, active == null ? 0 : active);
        // the fixed deposits in the treasury belong to the players (counted in the money supply above)
        long treasury = Treasury.balance() - Bank.owedDeposits() - Bonds.owed();

        Snapshot previous = latestBefore(day);
        // stored on the first measurement too, so the second one already has something to compare
        Double growth = cohortGrowth(day, previous == null ? null : previous.day());
        ConfigManager config = ConfigManager.getManager();
        Step step = nextFactor(previous, growth, treasury / activePlayers, config.getEconomyTargetGrowth(),
                config.getTreasuryTargetPerPlayer(), config.getEconomyFactorMin(), config.getEconomyFactorMax());
        // while broke the highest factor applies; stored as such, the taxes sink from there after the recovery
        if (Bankruptcy.isBroke()) step = new Step(config.getEconomyFactorMax(), BROKE_NOTE);

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

    private static final String BROKE_NOTE = "Staat pleite: Steuern am Höchstwert, bis er sich erholt hat";
    private static final String RECOVERED_NOTE = "Staat erholt: Steuern starten beim Höchstwert und sinken schrittweise";

    /**
     * Blocking, when the state has recovered: the taxes start again from the highest factor and sink from there with
     * the daily measurement (at most MAX_DAILY_STEP a day, not while the treasury is almost empty), instead of falling
     * back at once to where they were before the bankruptcy. The latest measurement stores it, the next one builds on it.
     */
    static synchronized void recovered() {
        double max = ConfigManager.getManager().getEconomyFactorMax();
        // also when the latest measurement already stored max (it ran while broke): the note and the trend change anyway,
        // players must not see "erhöht ▲" right after the recovery
        Database.update("UPDATE economy_snapshots SET factor = GREATEST(factor, ?), note = ? WHERE day = (SELECT MAX(day) FROM economy_snapshots)",
                max, RECOVERED_NOTE);
        factor = Math.max(factor, max);
        previousFactor = factor;
        note = RECOVERED_NOTE;
    }

    /**
     * Blocking: stores what every holder has today (active players: account, running deposits and bonds minus the
     * principal of open loans; all team treasuries as one holder, so founding or dissolving a team moves nothing) and
     * returns the growth of the holders measured on both days, null if there is nothing to compare.
     * The dates are cast in the SQL: inside UNION ALL an untyped parameter would become text.
     */
    private static @Nullable Double cohortGrowth(LocalDate day, @Nullable LocalDate previousDay) {
        Date today = Date.valueOf(day);
        Database.inTransaction(connection -> {
            Database.update(connection, "DELETE FROM economy_wealth WHERE day = ? OR day < ?", today, Date.valueOf(day.minusDays(WEALTH_KEEP_DAYS)));
            Database.update(connection, """
                    INSERT INTO economy_wealth (day, holder, amount)
                    SELECT CAST(? AS DATE), 'p:' || p.uuid, p.money + COALESCE(d.amount, 0) + COALESCE(b.amount, 0) + COALESCE(g.amount, 0) - COALESCE(l.principal, 0)
                    FROM players p
                    LEFT JOIN (SELECT player_uuid, SUM(amount) AS amount FROM bank_deposits WHERE NOT paid_out GROUP BY player_uuid) d ON d.player_uuid = p.uuid
                    LEFT JOIN (SELECT player_uuid, SUM(amount) AS amount FROM state_bonds WHERE NOT repaid GROUP BY player_uuid) b ON b.player_uuid = p.uuid
                    LEFT JOIN (SELECT player_uuid, SUM(principal) AS principal FROM bank_loans WHERE state = 'OPEN' GROUP BY player_uuid) l ON l.player_uuid = p.uuid
                    LEFT JOIN (SELECT sponsor_uuid, SUM(amount) AS amount FROM bounty_pledges WHERE paid_at IS NULL AND refunded_at IS NULL GROUP BY sponsor_uuid) g ON g.sponsor_uuid = p.uuid
                    WHERE p.last_seen >= now() - make_interval(days => ?)
                    UNION ALL
                    SELECT CAST(? AS DATE), 't:all', COALESCE(SUM(treasury), 0) FROM teams""", today, ACTIVE_DAYS, today);
            return null;
        });
        if (previousDay == null) return null;
        long[] sums = Database.queryOne("""
                SELECT COALESCE(SUM(t.amount), 0), COALESCE(SUM(y.amount), 0), COUNT(*)
                FROM economy_wealth t JOIN economy_wealth y ON y.holder = t.holder AND y.day = ?
                WHERE t.day = ?""", row -> new long[]{row.getLong(1), row.getLong(2), row.getLong(3)}, Date.valueOf(previousDay), today);
        // nobody to compare, or too little money to give a meaningful rate
        if (sums == null || sums[2] == 0 || sums[1] < MIN_COMPARED_MONEY) return null;
        return (double) sums[0] / sums[1] - 1.0;
    }

    // below this sum (the holders measured on both days) a growth rate is noise
    private static final long MIN_COMPARED_MONEY = 1_000;

    /** The controller itself, without database access. growth: of the same holders since the previous measurement, null if unknown. */
    static Step nextFactor(@Nullable Snapshot previous, @Nullable Double growth, long treasuryPerPlayer, double targetGrowth,
                           int treasuryTarget, double min, double max) {
        if (previous == null) {
            return new Step(clamp(1.0, min, max), "Erste Messung, Steuern normal");
        }
        if (growth == null) {
            return new Step(clamp(previous.factor(), min, max), "Noch kein Vergleich mit gestern möglich: Steuern stabil");
        }
        double step = clamp(growth - targetGrowth, -MAX_DAILY_STEP, MAX_DAILY_STEP);
        String reason = String.format(Locale.GERMANY, "Geld der Spieler %+.1f %% seit gestern (Ziel %+.1f %%)", growth * 100, targetGrowth * 100);
        // a full treasury lowers the taxes only while the money is not growing faster than wanted
        if (treasuryTarget > 0 && treasuryPerPlayer > treasuryTarget && growth <= targetGrowth) {
            step -= TREASURY_STEP;
            reason += ", Staatskasse gut gefüllt";
        } else if (treasuryTarget > 0 && treasuryPerPlayer < treasuryTarget * Bankruptcy.RECOVERED_SHARE) {
            // an almost empty treasury (own money under the recovery line: deposits and bonds counted off) never lowers
            // the taxes, it raises them a little every day until the state has money of its own again
            step = Math.min(MAX_DAILY_STEP, Math.max(step, 0) + TREASURY_STEP);
            reason += ", Staatskasse fast leer";
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

    /** Blocking: stores the AI review of the day, the players' report (null: none today) and the admins' note. */
    static void saveAiReview(@NotNull LocalDate day, @Nullable String report, @Nullable String adminNote) {
        Database.update("UPDATE economy_snapshots SET ai_summary = ?, ai_admin = ? WHERE day = ?", report, adminNote, Date.valueOf(day));
    }

    public record Review(LocalDate day, String text) {}

    /** Blocking: the players' market report, only from today or yesterday (an old one would pass for today's). */
    public static @Nullable Review latestReport() {
        return Database.queryOne("SELECT day, ai_summary FROM economy_snapshots WHERE ai_summary IS NOT NULL AND day >= ? ORDER BY day DESC LIMIT 1",
                row -> new Review(row.getDate(1).toLocalDate(), row.getString(2)), Date.valueOf(LocalDate.now(Market.ZONE).minusDays(1)));
    }

    /** Blocking: the latest note of the AI for the admins. */
    public static @Nullable Review latestAdminNote() {
        return Database.queryOne("SELECT day, ai_admin FROM economy_snapshots WHERE ai_admin IS NOT NULL ORDER BY day DESC LIMIT 1",
                row -> new Review(row.getDate(1).toLocalDate(), row.getString(2)));
    }

    /** Blocking: the measurement of that day, null if it was not measured. */
    public static @Nullable Snapshot snapshot(@NotNull LocalDate day) {
        Snapshot latest = latestBefore(day.plusDays(1));
        return latest != null && latest.day().equals(day) ? latest : null;
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

}
