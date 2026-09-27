package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.economy.Economy;
import xyz.jupp.minecraft.economy.Treasury;

// main thread only
public class TabListUtil {

    private static final String HEADER = "Auslastung: ";
    // kept short on purpose: the details are in the shop info tab and /staatskasse
    private static final String FOOTER = "\n§6Staatskasse: §a%d Schilling\n§7Steuern: %s";


    public static void updateTabForAll() {
        Component header = header();
        Component footer = footer();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPlayerListHeaderAndFooter(header, footer);
        }
    }

    public static void updateTabFor(Player player) {
        player.sendPlayerListHeaderAndFooter(header(), footer());
    }

    private static Component header() {
        return Component.text(HEADER + currentPerformance() + "\n");
    }

    private static Component footer() {
        return Component.text(FOOTER.formatted(Treasury.balance(), Economy.levelWord()));
    }

    private static String currentPerformance() {
        double[] tpsArr = Bukkit.getTPS();
        double tps1m = tpsArr[0];

        if (tps1m >= 19.8) {
            return "§aGering";
        }
        if (tps1m >= 17.0) {
            return "§6Mittel";
        }
        return "§cHoch";
    }

}
