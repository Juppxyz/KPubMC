package xyz.jupp.minecraft.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;

/**
 * KPubMC's money for other plugins (the casino plugin). Get it once in your onEnable with {@link #get()}; your
 * plugin.yml needs {@code depend: [KPubMC]}, so KPubMC is enabled first.
 * <p>
 * What another plugin may do, and nothing more:
 * <ul>
 *     <li>read cash: is an item a valid note, what is it worth, how much cash does a player carry ({@link CashNotes});</li>
 *     <li>take a bet in cash from a player into the casino's own till, and pay a win in cash out of it ({@link CasinoBank}).</li>
 * </ul>
 * No method credits a bank account, creates a note without the till paying for it, or touches the treasury. The casino
 * can never pay out more than its till holds: its start capital plus what players lost there.
 * <p>
 * Seasons: an admin can reset the season (all money, accounts and the casino till start over). Notes printed in an
 * older season are worth nothing afterwards. {@link #season()} tells which season it is; store it with your own data
 * (jackpots, statistics) and start over when it changed.
 */
public interface KPubEconomy {

    /** Raised when the API changes in a way that breaks plugins built against an older version. */
    int API_VERSION = 1;

    /**
     * The service KPubMC registers in its onEnable.
     *
     * @throws IllegalStateException if KPubMC is not enabled (missing depend, or KPubMC failed to start)
     */
    static @NotNull KPubEconomy get() {
        RegisteredServiceProvider<KPubEconomy> provider = Bukkit.getServicesManager().getRegistration(KPubEconomy.class);
        if (provider == null) throw new IllegalStateException("KPubMC is not enabled (plugin.yml needs depend: [KPubMC])");
        return provider.getProvider();
    }

    /** The API version of the running KPubMC; compare it with {@link #API_VERSION} of the jar you built against. */
    int apiVersion();

    /** The currency's name, e.g. "Schilling". */
    @NotNull String currencyName();

    /** The current season (0 before the first reset); notes of any other season are worth nothing. */
    int season();

    /** Reading cash: notes, values, what a player carries. */
    @NotNull CashNotes cash();

    /** The casino's till: bets in, wins out, both in cash. */
    @NotNull CasinoBank casino();

}
