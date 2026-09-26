package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;

import java.util.Collection;
import java.util.Random;

import static xyz.jupp.minecraft.utils.Locations.isLocationASpawn;
import static xyz.jupp.minecraft.utils.MobEvent.createMobEvent;


public class PlayerUpdaterTask {

    private int serverEmptyCheck = 0;

    public boolean startTask() {
        Bukkit.getScheduler().scheduleAsyncRepeatingTask(Main.getInstance(), () -> {
            Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "§fupdating players data..");

            Random random = new Random();
            boolean isMonsterEvent = random.nextInt(200) == 0;

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                TabListUtil.updateTabForAll();
                Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();

                if (onlinePlayers.isEmpty()) {
                    // 6 Stunden
                    if (serverEmptyCheck >= 72) {
                        Bukkit.shutdown();
                    }
                    serverEmptyCheck++;
                    Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "increased emptyServerCheck to " + serverEmptyCheck);
                }else {
                    serverEmptyCheck = 0;
                    Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "reset emptyServerCheck");
                }

                for (Player player : onlinePlayers) {
                    PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                    playerCacheObject.updatePlayer();

                    Location bedSpawn = null;
                    if (player.isSleeping()) {
                        bedSpawn = player.getBedLocation();
                    }
                    if (bedSpawn == null) {
                        bedSpawn = player.getRespawnLocation();
                    }
                    if ((bedSpawn != null) && !isLocationASpawn(bedSpawn) && bedSpawn.getWorld().equals(player.getWorld())) {
                        double distance = bedSpawn.distance(player.getLocation());
                        if ( distance <= 160 && isMonsterEvent) {
                            player.sendMessage(Main.getChatPrefix() + "§cSicherheitsmeldung: Ungeziefer im Schlafbereich erkannt.");
                            Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "created monster event for player " + player.getName() + " at " + bedSpawn.toString());
                            createMobEvent(player);
                        }
                    }

                }
            });

            Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "§ffinished updating players data");
        }, 0, 20L * 900);
        return false;
    }

}
