package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Economy switches the admins set in game ({@code /wirtschaft}), stored in market_meta as 'setting.&lt;key&gt;' and
 * cached (read on any thread). A setting without a stored value uses its default, from the config where it has one.
 * The netherite and loan switches belong to the season: a season reset puts them back (SeasonReset.SEASON_META).
 */
public final class AdminSettings {

    private AdminSettings() {}

    static final String PREFIX = "setting.";

    /** Netherite at Hondo: automatic (from {@link #NETHERITE_AUTO_DAY} of the season on), open or closed. */
    public enum NetheriteMode { AUTO, OPEN, CLOSED }

    public static final String NETHERITE_MODE = "netherite_mode";
    public static final String DIAMOND_EARLY = "diamond_early";
    public static final String NETHERITE_PER_DAY = "netherite_per_day";
    public static final String CASH_WITHDRAW_TAX = "cash_withdraw_tax";
    public static final String CASH_DEPOSIT_TAX = "cash_deposit_tax";
    public static final String SATURATED_HOURS = "saturated_hours";
    public static final String LOANS_OPEN = "loans_open";
    public static final String AI_REVIEW = "ai_review";

    /** In automatic mode Hondo sells netherite from this day of the season on (one week after it began). */
    public static final int NETHERITE_AUTO_DAY = 8;
    public static final int NETHERITE_PER_DAY_DEFAULT = 2;
    public static final double SATURATED_HOURS_DEFAULT = 12;

    private static final Map<String, String> values = new ConcurrentHashMap<>();

    /** Blocking, in onEnable after a season reset could have run. */
    public static void load() {
        values.clear();
        Database.query("SELECT key, value FROM market_meta WHERE key LIKE 'setting.%'", row -> {
            values.put(row.getString(1).substring(PREFIX.length()), row.getString(2));
            return null;
        });
    }

    /** Blocking: stores the value, null removes it (back to the default). */
    public static void set(@NotNull String key, @Nullable String value, @NotNull String by) {
        if (value == null) {
            Database.update("DELETE FROM market_meta WHERE key = ?", PREFIX + key);
            values.remove(key);
        } else {
            Database.update("INSERT INTO market_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                    PREFIX + key, value);
            values.put(key, value);
        }
        Main.getInstance().getSLF4JLogger().info("economy setting {} = {} (by {})", key, value == null ? "default" : value, by);
    }

    /** Whether the admins set the value (not the default). */
    public static boolean isSet(@NotNull String key) {
        return values.containsKey(key);
    }


    /* the settings */

    public static NetheriteMode netheriteMode() {
        try {
            String value = values.get(NETHERITE_MODE);
            return value == null ? NetheriteMode.AUTO : NetheriteMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return NetheriteMode.AUTO;
        }
    }

    /** Diamonds dearer in the first days of a season (Hondo.earlyFactor). */
    public static boolean diamondEarly() {
        return !"false".equals(values.get(DIAMOND_EARLY));
    }

    public static int netheritePerDay() {
        // at least the friendship offer (2), it would never be claimable otherwise
        return (int) number(NETHERITE_PER_DAY, NETHERITE_PER_DAY_DEFAULT, NETHERITE_PER_DAY_DEFAULT, 16);
    }

    public static double cashWithdrawTax() {
        return number(CASH_WITHDRAW_TAX, ConfigManager.getManager().getCashWithdrawTax(), 0, 0.5);
    }

    public static double cashDepositTax() {
        return number(CASH_DEPOSIT_TAX, ConfigManager.getManager().getCashDepositTax(), 0, 0.5);
    }

    public static double saturatedHours() {
        return number(SATURATED_HOURS, SATURATED_HOURS_DEFAULT, 1, 72);
    }

    /** Basil gives new loans (running ones go on as usual). */
    public static boolean loansOpen() {
        return !"false".equals(values.get(LOANS_OPEN));
    }

    /** The AI market review runs (also needs the config's API key). */
    public static boolean aiReview() {
        String value = values.get(AI_REVIEW);
        return value == null ? ConfigManager.getManager().isAiReview() : !"false".equals(value);
    }

    private static double number(String key, double fallback, double min, double max) {
        String value = values.get(key);
        if (value == null) return fallback;
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(value)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

}
