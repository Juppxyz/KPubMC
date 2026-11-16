package xyz.jupp.minecraft.api;

import org.jetbrains.annotations.NotNull;

/**
 * The outcome of a booking at the casino's till.
 *
 * @param status        what happened
 * @param amount        the amount that was asked for
 * @param casinoBalance what the till holds afterwards
 */
public record CasinoResult(@NotNull Status status, long amount, long casinoBalance) {

    public enum Status {
        /** Booked: the bet is in the till, the win was handed out, or the money is in the treasury. */
        OK,
        /** Not a multiple of 10, not positive, or above {@link CasinoBank#MAX_AMOUNT}. Nothing happened. */
        INVALID_AMOUNT,
        /** The player does not carry enough valid notes in the storage slots. Nothing was taken. */
        NOT_ENOUGH_CASH,
        /** The change of the bet would not fit into the player's inventory (it would drop). Nothing was taken. */
        INVENTORY_FULL,
        /**
         * The till cannot cover the win, or the player reached the daily payout limit, or (payToTreasury) the surplus
         * above the start capital is too small. Nothing was paid.
         */
        CASINO_EMPTY,
        /** An admin has stopped the casino. Nothing happened. */
        CLOSED,
        /** The player is not online (any more). Nothing was taken, or a win went back into the till. */
        PLAYER_GONE,
        /**
         * The booking failed (database). A bet: the notes were given back (lost only if the player left in that very
         * moment, the server log says so). A win: nothing was paid.
         */
        FAILED
    }

    public boolean ok() {
        return status == Status.OK;
    }

}
