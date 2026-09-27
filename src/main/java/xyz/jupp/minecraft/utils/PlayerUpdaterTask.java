package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;

public class PlayerUpdaterTask {

    // 72 empty runs of 15 minutes, the shutdown follows in the 73rd empty run (about 18 h)
    private static final int MAX_EMPTY_RUNS = 72;

    // only used on the main thread
    private int serverEmptyCheck = 0;

    public boolean startTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), this::update, 0, 20L * 900);
        return false;
    }

    // async worker: the cache lookups may hit the database, everything else goes to the main thread
    private void update() {
        Tasks.sync(this::updateOnMainThread);

        for (Player player : Bukkit.getOnlinePlayers()) {
            CacheHandler.getInstance().getPlayerInCache(player).updatePlayer();
        }
    }

    private void updateOnMainThread() {
        TabListUtil.updateTabForAll();

        if (Bukkit.getOnlinePlayers().isEmpty()) {
            if (serverEmptyCheck >= MAX_EMPTY_RUNS) {
                Bukkit.shutdown();
            }
            serverEmptyCheck++;
            Logger.console("increased emptyServerCheck to " + serverEmptyCheck);
            return;
        }
        serverEmptyCheck = 0;
    }

}
