package xyz.jupp.minecraft.utils;

import org.bukkit.entity.Player;

import java.time.Duration;

public class LastSeen {

    private static final long RETURNING_AFTER_MILLIS = Duration.ofDays(90).toMillis();

    // 0 = normal join, 1 = first join, 2 = back after more than 90 days
    public static int getJoinState(Player player) {
        if (!player.hasPlayedBefore()) {
            return 1;
        }
        // stays deprecated: for an online player only getLastPlayed still holds the end of the previous session
        long lastPlayed = player.getLastPlayed();
        long cutoff = System.currentTimeMillis() - RETURNING_AFTER_MILLIS;

        return lastPlayed < cutoff ? 2 : 0;
    }

}
