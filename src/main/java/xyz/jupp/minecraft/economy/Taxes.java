package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.Database;

import java.util.List;
import java.util.UUID;

/**
 * All taxes of the server. Every method that takes money is blocking and runs as one transaction together with the
 * treasury booking, so a tax can never be taken without arriving in the treasury.
 * Every rate is the configured base rate times the economy factor ({@link Economy#factor()}).
 */
public final class Taxes {

    private Taxes() {}

    // below these balances no tax is charged
    private static final int DEATH_TAX_EXEMPTION = 250;
    private static final double MAX_RATE = 0.9;

    public record BalanceTax(int tax, int balanceBefore) {
        public double effectiveRate() {
            return balanceBefore == 0 ? 0 : (double) tax / balanceBefore;
        }
    }

    public record Purchase(boolean success, int net, int tax) {
        public int total() {
            return net + tax;
        }
    }

    public record CashWithdrawal(boolean success, int cash, int tax) {}


    /* rates */

    public static double rate(@NotNull TaxClass taxClass) {
        return Math.min(MAX_RATE, ConfigManager.getManager().getTaxClassRate(taxClass) * Economy.factor());
    }

    public static int taxOn(int net, @NotNull TaxClass taxClass) {
        return (int) Math.round(net * rate(taxClass));
    }

    public static double deathRate() {
        return Math.min(MAX_RATE, ConfigManager.getManager().getDeathTax() * Economy.factor());
    }

    /** The nether brackets with the economy factor applied; the factor can lower them, but never above the configured top rate. */
    public static List<TaxBracket> netherBrackets() {
        List<TaxBracket> configured = ConfigManager.getManager().getNetherTransferTaxBrackets();
        double top = configured.stream().mapToDouble(TaxBracket::rate).max().orElse(0);
        double factor = Economy.factor();
        return configured.stream().map(bracket -> new TaxBracket(bracket.from(), Math.min(top, bracket.rate() * factor))).toList();
    }

    public static double topNetherRate() {
        return netherBrackets().stream().mapToDouble(TaxBracket::rate).max().orElse(0);
    }

    /** Progressive transfer tax: every part of the balance is taxed with the rate of its bracket. */
    public static int netherTaxFor(int balance) {
        List<TaxBracket> brackets = netherBrackets();
        double tax = 0;
        for (int i = 0; i < brackets.size(); i++) {
            int lower = brackets.get(i).from();
            int upper = i + 1 < brackets.size() ? brackets.get(i + 1).from() : Integer.MAX_VALUE;
            if (balance <= lower) break;
            tax += (Math.min(balance, upper) - (double) lower) * brackets.get(i).rate();
        }
        return (int) Math.round(tax);
    }


    /* charges (blocking) */

    public static BalanceTax chargeDeathTax(@NotNull UUID player) {
        double rate = deathRate();
        return chargeBalanceTax(player, Treasury.Source.DEATH_TAX,
                balance -> balance <= DEATH_TAX_EXEMPTION ? 0 : (int) Math.round(balance * rate));
    }

    public static BalanceTax chargeNetherTax(@NotNull UUID player) {
        return chargeBalanceTax(player, Treasury.Source.NETHER_TAX, Taxes::netherTaxFor);
    }

    private interface TaxFunction {
        int taxFor(int balance);
    }

    private static BalanceTax chargeBalanceTax(UUID player, Treasury.Source source, TaxFunction function) {
        BalanceTax result = Database.inTransaction(connection -> {
            Integer balance = Database.queryOne(connection, "SELECT money FROM players WHERE uuid = ? FOR UPDATE",
                    row -> row.getInt(1), player);
            if (balance == null) return new BalanceTax(0, 0);
            int tax = Math.min(balance, Math.max(0, function.taxFor(balance)));
            if (tax > 0) {
                Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ?", tax, player);
                Treasury.deposit(connection, source, tax, player);
            }
            return new BalanceTax(tax, balance);
        });
        Treasury.committed(result.tax());
        return result;
    }

    /** Takes net + tax for a fixed-price purchase (jeweler, black market); the tax goes to the treasury. */
    public static Purchase chargePurchase(@NotNull UUID player, int net, @NotNull TaxClass taxClass) {
        int tax = taxOn(net, taxClass);
        boolean success = Database.inTransaction(connection -> {
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?",
                    net + tax, player, net + tax) == 0) return false;
            Treasury.deposit(connection, Treasury.Source.TRADE_TAX, tax, player);
            return true;
        });
        if (success) Treasury.committed(tax);
        return new Purchase(success, net, tax);
    }

    /**
     * Cash withdrawal: the requested amount minus the standard tax is paid out in cash notes of 10;
     * only the paid-out cash and the tax leave the account (the rest below 10 stays on it).
     */
    public static CashWithdrawal withdrawCash(@NotNull UUID player, int requested) {
        int tax = taxOn(requested, TaxClass.STANDARD);
        int cash = Math.max(0, requested - tax) / 10 * 10;
        if (cash == 0) return new CashWithdrawal(false, 0, 0);
        boolean success = Database.inTransaction(connection -> {
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?",
                    cash + tax, player, requested) == 0) return false;
            Treasury.deposit(connection, Treasury.Source.WITHDRAW_TAX, tax, player);
            return true;
        });
        if (success) Treasury.committed(tax);
        return new CashWithdrawal(success, cash, tax);
    }

}
