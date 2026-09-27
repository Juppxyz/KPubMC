package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.economy.Economy;
import xyz.jupp.minecraft.economy.TaxClass;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.economy.Treasury;

import java.util.Locale;

// main thread only
public class TabListUtil {

    private static final String HEADER = "Auslastung: ";
    private static final String FOOTER = "\n§fSteuern: §a%s §8| §fTod: §a%d%% §8| §fTransfer: §abis %d%%\n§fKonjunktur: %s §8| §6Staatskasse: §a%d Schilling";


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
        String classes = Math.round(Taxes.rate(TaxClass.BASIC) * 100) + "§8/§a" + Math.round(Taxes.rate(TaxClass.STANDARD) * 100)
                + "§8/§a" + Math.round(Taxes.rate(TaxClass.LUXURY) * 100) + "%";
        String factor = "§f×" + String.format(Locale.GERMANY, "%.2f", Economy.factor()) + " " + Economy.trendSymbol();
        return Component.text(FOOTER.formatted(classes, Math.round(Taxes.deathRate() * 100),
                Math.round(Taxes.topNetherRate() * 100), factor, Treasury.balance()));
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
