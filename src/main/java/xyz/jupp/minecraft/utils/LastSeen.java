package xyz.jupp.minecraft.utils;
import org.bukkit.entity.Player;

public class LastSeen {

    public static int getJoinState(Player player) {
        if (!player.hasPlayedBefore()) {
            return 1;
        }
        long lastPlayed = player.getLastPlayed();
        long cutoff = System.currentTimeMillis()
                - java.time.Duration.ofDays(90).toMillis();

        return lastPlayed < cutoff ? 2 : 0;
    }

}