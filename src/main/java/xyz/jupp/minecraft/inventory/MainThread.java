package xyz.jupp.minecraft.inventory;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands the result of a booking from a worker thread back to the main thread (next tick).
 */
public final class MainThread {

    private MainThread() {}

    // scheduled but not run yet: a server stop cancels them, onDisable runs them instead
    private static final Set<Runnable> PENDING = ConcurrentHashMap.newKeySet();

    // false if the plugin is already disabled (server stop), the task is dropped then
    public static boolean run(@NotNull Runnable task) {
        Runnable once = new Runnable() {
            @Override
            public void run() {
                if (PENDING.remove(this)) task.run();
            }
        };
        PENDING.add(once);
        try {
            Tasks.sync(once);
            return true;
        } catch (IllegalPluginAccessException e) {
            PENDING.remove(once);
            return false;
        }
    }

    /** onDisable, main thread: what was scheduled but gets no tick anymore (players are still online then). */
    public static void runPending() {
        for (Runnable once : List.copyOf(PENDING)) {
            try {
                once.run();
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().error("A delivery could not run at shutdown", e);
            }
        }
    }

    // after a successful withdrawal on a worker: the delivery runs on the main thread, the amount is refunded if it cannot run anymore
    public static void deliverOrRefund(@NotNull UUID payer, int amount, @NotNull Runnable delivery) {
        if (!run(delivery)) PlayerRepository.addMoney(payer, amount);
    }

}
