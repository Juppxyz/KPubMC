package xyz.jupp.minecraft.economy;

import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.utils.BlackMarketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Haggling with Morpheus (main thread only). The item has a secret minimum price; Morpheus opens with a higher asking
 * price. An offer at or above the secret price is accepted. An offer slightly below it gets a counter offer and costs
 * patience. A far too low offer, or running out of patience, ends the talk: Morpheus refuses to sell to this player
 * until his next visit.
 */
final class Haggle {

    private Haggle() {}

    // offers below this share of the secret price are an insult
    static final double INSULT_SHARE = 0.6;
    static final int PATIENCE = 3;

    enum Answer { DEAL, COUNTER, INSULTED, GAVE_UP }

    record Result(Answer answer, int price) {}

    record Talk(int offerId, int asking, int lastOffer, int patience) {}

    private static final Map<UUID, Talk> talks = new HashMap<>();
    private static final Map<UUID, Integer> refusedUntilVisit = new HashMap<>();

    static boolean isRefused(@NotNull UUID player) {
        Integer visit = refusedUntilVisit.get(player);
        return visit != null && visit == BlackMarketHandler.currentVisit();
    }

    /** The running talk about the current item; a new item starts a new talk (reopening the menu does not). */
    static Talk talk(@NotNull UUID player) {
        Talk talk = talks.get(player);
        int offerId = BlackMarketHandler.currentOffer();
        if (talk == null || talk.offerId() != offerId) {
            talk = new Talk(offerId, BlackMarketHandler.getAskingPrice(), 0, PATIENCE);
            talks.put(player, talk);
        }
        return talk;
    }

    static Result offer(@NotNull UUID player, int amount) {
        Talk talk = talk(player);
        int secret = BlackMarketHandler.getSecretPrice();
        if (amount >= talk.asking()) return new Result(Answer.DEAL, talk.asking());
        if (amount >= secret) return new Result(Answer.DEAL, amount);
        if (amount < secret * INSULT_SHARE) {
            refuse(player);
            return new Result(Answer.INSULTED, 0);
        }
        int patience = talk.patience() - 1;
        if (patience <= 0) {
            refuse(player);
            return new Result(Answer.GAVE_UP, 0);
        }
        // he comes closer, but never below his secret price
        int asking = Math.max(secret, (int) (Math.round((talk.asking() + amount) / 2.0 / 50.0) * 50));
        talks.put(player, new Talk(talk.offerId(), asking, amount, patience));
        return new Result(Answer.COUNTER, asking);
    }

    private static void refuse(UUID player) {
        talks.remove(player);
        refusedUntilVisit.put(player, BlackMarketHandler.currentVisit());
    }

    static void finish(@NotNull UUID player) {
        talks.remove(player);
    }

}
