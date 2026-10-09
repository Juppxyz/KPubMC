package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;

public class PlayerUpdaterTask {

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
    }

}
