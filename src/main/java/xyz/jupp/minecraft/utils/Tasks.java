package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Small wrapper around the Bukkit scheduler (the server is not Folia).
 * async: worker thread for blocking work (database), sync: main thread for Bukkit API calls.
 */
public final class Tasks {

    private Tasks() {}

    public static BukkitTask async(@NotNull Runnable task) {
        return Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), task);
    }

    public static BukkitTask sync(@NotNull Runnable task) {
        return Bukkit.getScheduler().runTask(Main.getInstance(), task);
    }

    public static BukkitTask syncLater(long delayTicks, @NotNull Runnable task) {
        return Bukkit.getScheduler().runTaskLater(Main.getInstance(), task, delayTicks);
    }

    /**
     * Runs the supplier on a worker thread and hands its result to the consumer on the main thread.
     * If the supplier throws, the consumer is not called and the scheduler logs the exception.
     */
    public static <T> BukkitTask supplyAsync(@NotNull Supplier<T> supplier, @NotNull Consumer<T> onMainThread) {
        return async(() -> {
            T result = supplier.get();
            sync(() -> onMainThread.accept(result));
        });
    }

}
