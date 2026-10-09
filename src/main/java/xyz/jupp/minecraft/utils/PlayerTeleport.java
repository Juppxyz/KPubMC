package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.Main;

public class PlayerTeleport {

    // may be called from any thread, the check and the teleport run on the main thread
    public void teleportAfter(Player player, Location targetLocation) {
        if (!Bukkit.isPrimaryThread()) {
            Tasks.sync(() -> teleportAfter(player, targetLocation));
            return;
        }
        if (CombatLock.denies(player)) return;

        player.sendMessage(Main.getChatPrefix() + "§6Nicht bewegen, du wirst in 5 Sekunden teleportiert..");
        Location initialLocation = player.getLocation();
        long delayTicks = 5 * 20;
        Tasks.syncLater(delayTicks, () -> {
            if (!player.isOnline()) return;
            Location currentLocation = player.getLocation();
            // after a world change the distance check used to throw, nothing else happened
            if (currentLocation.getWorld() != initialLocation.getWorld()) return;
            // a hit during the countdown that did not move the player (no knockback)
            if (CombatLock.isInCombat(player)) {
                player.sendMessage(Main.getChatPrefix() + "§cDu bist in einen Kampf geraten, der Teleport wurde abgebrochen.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f,1f);
                return;
            }

            if (currentLocation.distanceSquared(initialLocation) == 0) {
                player.playSound(initialLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1f,1f);
                player.teleport(targetLocation);
            } else {
                player.sendMessage(Main.getChatPrefix() + "§fBleib bitte stehen, um teleportiert zu werden.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f,1f);
            }
        });
    }
}
