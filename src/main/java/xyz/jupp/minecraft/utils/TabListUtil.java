package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.economy.Treasury;

// main thread only
public class TabListUtil {

    private static final String HEADER = "Auslastung: ";
    private static final String FOOTER = "\n§fHandel: §a%d%% §8| §fTod: §a%d%% §8| §fTransfer: §abis %d%%\n§6Staatskasse: §a%d Schilling";


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
        return Component.text(FOOTER.formatted(Math.round(Taxes.tradeRate() * 100), Math.round(Taxes.deathRate() * 100),
                Math.round(Taxes.topNetherRate() * 100), Treasury.balance()));
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
