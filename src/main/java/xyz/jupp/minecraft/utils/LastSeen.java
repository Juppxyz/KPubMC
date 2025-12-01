package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.time.Instant;
import java.time.Period;

public class LastSeen {

    public static boolean isInactiveAtLeast3MonthsOrNever(String playerName) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
        if (op.isOnline()) {
            return false;
        }
        long lastSeen = op.getLastSeen();
        if (lastSeen == 0L) {
            return true;
        }
        long cutoff = Instant.now().minus(Period.ofMonths(3)).toEpochMilli();
        return lastSeen < cutoff;
    }

}
