package xyz.jupp.minecraft.utils;

import org.bukkit.inventory.ItemStack;
import xyz.jupp.minecraft.items.BedrockBreakerPickaxe;
import xyz.jupp.minecraft.items.CustomItemsInterface;
import xyz.jupp.minecraft.items.Flamethrower;
import xyz.jupp.minecraft.items.ForgedPapers;
import xyz.jupp.minecraft.items.GrapplingHook;
import xyz.jupp.minecraft.items.KeepInventoryItem;
import xyz.jupp.minecraft.items.PoisonBow;
import xyz.jupp.minecraft.items.TrackerCompass;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Morpheus, the black market dealer: every hour he is there with a chance of 1 in 3 and offers one item.
 * The price is secret (minimum price + 10-50 %); he opens the haggling with a higher asking price.
 * A visit lasts as long as he stays (consecutive hours); a new visit starts when he comes back.
 */
public final class BlackMarketHandler {

    private static final List<CustomItemsInterface> blackMarketItems = List.of(
            new Flamethrower(),
            new PoisonBow(),
            new BedrockBreakerPickaxe(),
            new KeepInventoryItem(),
            new TrackerCompass(),
            new ForgedPapers(),
            new GrapplingHook()
    );

    private static final long HOUR_MILLIS = 3_600_000L;

    private static final AtomicLong lastRollHour    = new AtomicLong(-1);
    private static final AtomicLong lastOpenHour    = new AtomicLong(-2);
    private static final AtomicBoolean isOpen       = new AtomicBoolean(false);
    private static final AtomicInteger currentItem  = new AtomicInteger(-1);
    private static final AtomicInteger secretPrice  = new AtomicInteger(-1);
    private static final AtomicInteger askingPrice  = new AtomicInteger(-1);
    private static final AtomicInteger visit        = new AtomicInteger();
    private static final AtomicInteger offer        = new AtomicInteger();
    // the offer that was just sold (so two buyers cannot get the same item)
    private static final AtomicInteger soldOffer    = new AtomicInteger(-1);

    private BlackMarketHandler() {}

    private static boolean rollOpen() {
        return ThreadLocalRandom.current().nextInt(3) == 0;
    }

    private static void roll(long hour) {
        boolean open = rollOpen();
        if (open) {
            // he was not there in the previous hour: a new visit
            if (lastOpenHour.get() < hour - 1) visit.incrementAndGet();
            lastOpenHour.set(hour);
        }
        isOpen.set(open);
        rerollCurrentItemIndex();
    }

    private static void rerollCurrentItemIndex() {
        int idx = ThreadLocalRandom.current().nextInt(blackMarketItems.size());
        currentItem.set(idx);
        int minPrice = blackMarketItems.get(idx).getMinCost();
        int secret = Math.toIntExact(Math.round(minPrice + (randomProbabilitySkewed() * minPrice)));
        secretPrice.set(secret);
        // he opens 30-60 % above his secret price
        double markup = 1.3 + ThreadLocalRandom.current().nextDouble() * 0.3;
        askingPrice.set((int) (Math.round(secret * markup / 100.0) * 100));
        offer.incrementAndGet();
    }

    public static boolean isOpen() {
        long hour = System.currentTimeMillis() / HOUR_MILLIS;
        long previous = lastRollHour.get();
        if (hour != previous && lastRollHour.compareAndSet(previous, hour)) roll(hour);
        return isOpen.get();
    }

    public static void forceReroll() {
        roll(System.currentTimeMillis() / HOUR_MILLIS);
    }

    public static ItemStack getCurrentBlackMarketItem() {
        int idx = currentItem.get();
        if (idx < 0) return null;
        return blackMarketItems.get(idx).getItemStack();
    }

    private static double randomPercentSkewed(double minPercent, double maxPercent, double skewPower) {
        double u = ThreadLocalRandom.current().nextDouble();
        double biased = Math.pow(u, skewPower);
        return minPercent + (maxPercent - minPercent) * biased;
    }

    private static double randomProbabilitySkewed() {
        // 10-50 % surcharge on the minimum price, skewed towards 10 %
        return randomPercentSkewed(10.0, 50.0, 3.0) / 100.0;
    }

    /** The lowest price Morpheus accepts for the current item (never shown). */
    public static int getSecretPrice() {
        return secretPrice.get();
    }

    /** The price Morpheus opens the haggling with. */
    public static int getAskingPrice() {
        return askingPrice.get();
    }

    public static int currentVisit() {
        return visit.get();
    }

    public static int currentOffer() {
        return offer.get();
    }

    /** Main thread: reserves the current offer for one buyer; false if it was just sold. */
    public static boolean reserve(int offerId) {
        if (offerId != offer.get()) return false;
        int sold = soldOffer.get();
        return sold != offerId && soldOffer.compareAndSet(sold, offerId);
    }

    /** Releases a reservation whose payment failed. */
    public static void release(int offerId) {
        soldOffer.compareAndSet(offerId, -1);
    }

}
