package xyz.jupp.minecraft.inventory;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.UUID;

/**
 * Hands the result of a booking from a worker thread back to the main thread (next tick).
 */
public final class MainThread {

    private MainThread() {}

    // false if the plugin is already disabled (server stop), the task is dropped then
    public static boolean run(@NotNull Runnable task) {
        try {
            Tasks.sync(task);
            return true;
        } catch (IllegalPluginAccessException e) {
            return false;
        }
    }

    // after a successful withdrawal on a worker: the delivery runs on the main thread, the amount is refunded if it cannot run anymore
    public static void deliverOrRefund(@NotNull UUID payer, int amount, @NotNull Runnable delivery) {
        if (!run(delivery)) PlayerCollection.addMoney(payer, amount);
    }

}
