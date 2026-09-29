package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;

public class PlayerTeleport {

    // may be called from any thread, the check and the teleport run on the main thread
    public void teleportAfter(Player player, Location targetLocation) {
        teleportAfter(player, targetLocation, null);
    }

    // onAbort runs on the main thread if the player is not teleported (moved, left, changed world), e.g. to refund a price
    public void teleportAfter(Player player, Location targetLocation, @Nullable Runnable onAbort) {
        teleportAfter(player, targetLocation, onAbort, null);
    }

    // onDone runs on the main thread after the teleport happened
    public void teleportAfter(Player player, Location targetLocation, @Nullable Runnable onAbort, @Nullable Runnable onDone) {
        if (!Bukkit.isPrimaryThread()) {
            Tasks.sync(() -> teleportAfter(player, targetLocation, onAbort, onDone));
            return;
        }

        player.sendMessage(Main.getChatPrefix() + "§6Nicht bewegen, du wirst in 5 Sekunden teleportiert..");
        Location initialLocation = player.getLocation();
        long delayTicks = 5 * 20;
        Tasks.syncLater(delayTicks, () -> {
            if (!player.isOnline()) {
                abort(onAbort);
                return;
            }
            Location currentLocation = player.getLocation();
            // after a world change the distance check used to throw, nothing else happened
            if (currentLocation.getWorld() != initialLocation.getWorld()) {
                abort(onAbort);
                return;
            }

            if (currentLocation.distanceSquared(initialLocation) == 0) {
                player.playSound(initialLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1f,1f);
                if (!player.teleport(targetLocation)) {
                    abort(onAbort);
                } else if (onDone != null) {
                    onDone.run();
                }
            } else {
                player.sendMessage(Main.getChatPrefix() + "§fBleib bitte stehen, um teleportiert zu werden.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f,1f);
                abort(onAbort);
            }
        });
    }

    private static void abort(@Nullable Runnable onAbort) {
        if (onAbort != null) onAbort.run();
    }
}
