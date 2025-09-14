package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.jupp.minecraft.Main;

public class PlayerTeleport {

    public void teleportAfter(Player player, Location targetLocation) {
        player.sendMessage(Main.getChatPrefix() + "§6Nicht bewegen, du wirst in 5 Sekunden teleportiert..");
        Location initialLocation = player.getLocation().clone();
        long delayTicks = 5 * 20;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                if (player.getLocation().distanceSquared(initialLocation) == 0) {
                    player.playSound(initialLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1f,1f);
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        player.teleport(targetLocation);
                    });
                } else {
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        player.sendMessage(Main.getChatPrefix() + "§fBleib bitte stehen, um teleportiert zu werden.");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f,1f);
                    });
                }
            }
        }.runTaskLaterAsynchronously(Main.getInstance(), delayTicks);
    }
}
