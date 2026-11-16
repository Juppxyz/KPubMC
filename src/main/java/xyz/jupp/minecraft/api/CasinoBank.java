package xyz.jupp.minecraft.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * The casino's own till (Casinokasse), apart from the state treasury. It starts every season with a start capital set by
 * the admins (default 100,000), grows with every bet and shrinks with every win. Cash only: bets come from the notes a
 * player carries, wins are handed out as notes. Bank accounts are never touched.
 * <p>
 * Call both methods on the main thread. They check what they can right away, take or hand out the notes on the main
 * thread and book on a worker; the future always completes on the main thread, also when something failed (never
 * exceptionally), so {@code thenAccept(result -> ...)} may use the Bukkit API. While the server stops (e.g. refunds in
 * your onDisable) everything runs at once and the future is already complete when the method returns.
 * <p>
 * The till can run empty: a win larger than the balance is refused ({@link CasinoResult.Status#CASINO_EMPTY}) and
 * nothing is paid. Check {@link #balance()} before you accept a bet whose highest possible win the till cannot cover.
 * One player may take at most {@link #dailyPayoutLimit()} out of the till in 24 hours, net (wins minus bets); a win
 * beyond it is refused the same way (CASINO_EMPTY), keep it and pay it later ({@link #payoutLeft(Player)} says how
 * much fits now). An admin can close the casino with an emergency switch ({@link CasinoResult.Status#CLOSED}): then
 * wins and refunds are refused too; keep them and pay them once {@link #isOpen()} is true again.
 * <p>
 * What the casino collects for the state (e.g. exchange fees) goes straight into the state treasury with
 * {@link #payToTreasury(long, String)}, never as cash to a player.
 */
public interface CasinoBank {

    /** The largest single bet, win or payment to the treasury. */
    long MAX_AMOUNT = 1_000_000;

    /** What the till holds now (updated after every booking; good for "can we cover this bet"). */
    long balance();

    /** What the till started the season with. */
    long startCapital();

    /** False while an admin has stopped the casino; every booking returns CLOSED then. */
    boolean isOpen();

    /**
     * Takes a bet in cash: notes worth {@code amount} leave the player's storage slots right now (bigger notes are
     * changed, the change goes back into the inventory) and the amount goes into the till.
     *
     * @param amount a multiple of 10, from 10 to {@link #MAX_AMOUNT}
     * @param game   a short name of the game for the casino's log, e.g. "slots" (letters, digits, space, _ . : - only,
     *               cut at 32 characters)
     * @return OK; or INVALID_AMOUNT, CLOSED, PLAYER_GONE, NOT_ENOUGH_CASH, INVENTORY_FULL (nothing taken); or FAILED
     *         (the booking failed, the notes were given back)
     */
    @NotNull CompletableFuture<CasinoResult> takeBet(@NotNull Player player, long amount, @NotNull String game);

    /**
     * Pays a win in cash out of the till: the till is debited first, then the notes are handed to the player (what does
     * not fit drops at their feet). A refunded bet is paid the same way.
     *
     * @param amount a multiple of 10, from 10 to {@link #MAX_AMOUNT}
     * @param game   a short name of the game for the casino's log (letters, digits, space, _ . : - only, cut at 32 characters)
     * @return OK; or INVALID_AMOUNT, CLOSED, FAILED (nothing paid); or CASINO_EMPTY (the till cannot cover it, or the
     *         player reached the daily payout limit: nothing paid, pay it later); or PLAYER_GONE (the player left before
     *         the notes could be handed out, the amount went back into the till: pay it again when they are back)
     */
    @NotNull CompletableFuture<CasinoResult> payWin(@NotNull Player player, long amount, @NotNull String game);

    /** The most cash one player may take out of the till in 24 hours, net (wins minus bets); 0: no limit. */
    long dailyPayoutLimit();

    /**
     * How much the player may take out of the till right now under the daily payout limit ({@code Long.MAX_VALUE}
     * without a limit). Main thread; the future completes on the main thread.
     */
    @NotNull CompletableFuture<Long> payoutLeft(@NotNull Player player);

    /**
     * Pays money of the till into the state treasury (Staatskasse), e.g. the exchange fees the casino collected for the
     * state. Only the till's surplus above the season's start capital can go, i.e. money players left at the casino.
     * Main thread; the future completes on the main thread.
     *
     * @param amount from 1 to {@link #MAX_AMOUNT}
     * @param reason a short note for the casino's log (letters, digits, space, _ . : - only, cut at 32 characters)
     * @return OK; or INVALID_AMOUNT, CLOSED, CASINO_EMPTY (the surplus is too small), FAILED (nothing booked)
     */
    @NotNull CompletableFuture<CasinoResult> payToTreasury(long amount, @NotNull String reason);

}
