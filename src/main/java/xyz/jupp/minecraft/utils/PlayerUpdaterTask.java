package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;

import java.util.concurrent.ThreadLocalRandom;

import static xyz.jupp.minecraft.utils.Locations.isLocationASpawn;


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
        boolean isMonsterEvent = ThreadLocalRandom.current().nextInt(200) == 0;
        Tasks.sync(() -> updateOnMainThread(isMonsterEvent));

        for (Player player : Bukkit.getOnlinePlayers()) {
            CacheHandler.getInstance().getPlayerInCache(player).updatePlayer();
        }
    }

    private void updateOnMainThread(boolean isMonsterEvent) {
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

        if (isMonsterEvent) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                warnAboutMonsterEvent(player);
            }
        }
    }

    // Automatic monster events never spawned mobs: MobEvent.createMobEvent ran on an async thread, where Paper
    // rejects every entity spawn. Only this warning reached the players, and it stays that way until the owner
    // decides whether the automatic events should really spawn mobs (/debug still starts a real one).
    private static void warnAboutMonsterEvent(Player player) {
        Location bedSpawn = null;
        if (player.isSleeping()) {
            bedSpawn = player.getBedLocation();
        }
        if (bedSpawn == null) {
            bedSpawn = player.getRespawnLocation();
        }
        if ((bedSpawn != null) && !isLocationASpawn(bedSpawn) && bedSpawn.getWorld().equals(player.getWorld())) {
            double distance = bedSpawn.distance(player.getLocation());
            if (distance <= 160) {
                player.sendMessage(Main.getChatPrefix() + "§cSicherheitsmeldung: Ungeziefer im Schlafbereich erkannt.");
                Logger.console("created monster event for player " + player.getName() + " at " + bedSpawn);
            }
        }
    }

}
