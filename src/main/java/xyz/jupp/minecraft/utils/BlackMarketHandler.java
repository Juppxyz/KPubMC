package xyz.jupp.minecraft.utils;

import org.bukkit.inventory.ItemStack;
import xyz.jupp.minecraft.items.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class BlackMarketHandler {

    private static final Map<Integer, CustomItemsInterface> blackMarketItems = new HashMap<>();

    private static Map<Integer, CustomItemsInterface> getBlackMarketItems() {
        if (blackMarketItems.isEmpty()) {
            blackMarketItems.put(0, new Flamethrower());
            blackMarketItems.put(1, new PoisonBow());
            blackMarketItems.put(2, new BedrockBreakerPickaxe());
            blackMarketItems.put(3, new KeepInventoryItem());
        }
        return blackMarketItems;
    }

    private static final ZoneId ZONE                = ZoneId.of("Europe/Berlin");
    private static final AtomicInteger lastHour     = new AtomicInteger(-1);
    private static final AtomicBoolean isOpen       = new AtomicBoolean(true);
    private static final AtomicInteger currentItem  = new AtomicInteger(-1);
    private static final AtomicInteger currentCosts = new AtomicInteger(-1);

    private BlackMarketHandler() {}

    private static boolean rollOpen() {
        return ThreadLocalRandom.current().nextInt(3) == 0;
    }

    private static int rerollCurrentItemIndex() {
        int size = getBlackMarketItems().size();
        if (size <= 0) {
            currentItem.set(-1);
            return -1;
        }
        int idx = ThreadLocalRandom.current().nextInt(size);
        currentItem.set(idx);
        int minPrice = getBlackMarketItems().get(idx).getMinCost();
        double probability = randomProbabilitySkewed();
        currentCosts.set(Math.toIntExact(Math.round(minPrice + (probability * minPrice))));
        return idx;
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
        return getBlackMarketItems().get(idx).getItemStack();
    }


    public static double randomPercentSkewed() {
        return randomPercentSkewed(0.1, 0.5, 3.0);
    }

    public static double randomPercentSkewed(double minPercent, double maxPercent, double skewPower) {
        double u = ThreadLocalRandom.current().nextDouble();
        double biased = Math.pow(u, skewPower);
        return minPercent + (maxPercent - minPercent) * biased;
    }

    public static double randomProbabilitySkewed() {
        return randomPercentSkewed() / 100.0;
    }

    public static AtomicInteger getCurrentCosts() {
        return currentCosts;
    }
}
