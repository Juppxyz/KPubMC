package xyz.jupp.minecraft.utils;

import org.bukkit.inventory.ItemStack;
import xyz.jupp.minecraft.items.BedrockBreakerPickaxe;
import xyz.jupp.minecraft.items.CustomItemsInterface;
import xyz.jupp.minecraft.items.Flamethrower;
import xyz.jupp.minecraft.items.KeepInventoryItem;
import xyz.jupp.minecraft.items.PoisonBow;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class BlackMarketHandler {

    private static final List<CustomItemsInterface> blackMarketItems = List.of(
            new Flamethrower(),
            new PoisonBow(),
            new BedrockBreakerPickaxe(),
            new KeepInventoryItem()
    );

    private static final ZoneId ZONE                = ZoneId.of("Europe/Berlin");
    private static final AtomicInteger lastHour     = new AtomicInteger(-1);
    private static final AtomicBoolean isOpen       = new AtomicBoolean(true);
    private static final AtomicInteger currentItem  = new AtomicInteger(-1);
    private static final AtomicInteger currentCosts = new AtomicInteger(-1);

    private BlackMarketHandler() {}

    private static boolean rollOpen() {
        return ThreadLocalRandom.current().nextInt(3) == 0;
    }

    private static void rerollCurrentItemIndex() {
        int idx = ThreadLocalRandom.current().nextInt(blackMarketItems.size());
        currentItem.set(idx);
        int minPrice = blackMarketItems.get(idx).getMinCost();
        double probability = randomProbabilitySkewed();
        currentCosts.set(Math.toIntExact(Math.round(minPrice + (probability * minPrice))));
    }

    public static boolean isOpen() {
        int hour = ZonedDateTime.now(ZONE).getHour();
        int prev = lastHour.get();

        if (hour != prev && lastHour.compareAndSet(prev, hour)) {
            isOpen.set(rollOpen());
            rerollCurrentItemIndex();
        }
        return isOpen.get();
    }

    public static void forceReroll() {
        isOpen.set(rollOpen());
        rerollCurrentItemIndex();
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
        return randomPercentSkewed(0.1, 0.5, 3.0) / 100.0;
    }

    public static AtomicInteger getCurrentCosts() {
        return currentCosts;
    }
}
