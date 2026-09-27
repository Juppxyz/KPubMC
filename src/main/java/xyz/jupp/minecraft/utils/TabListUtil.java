package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.config.ConfigManager;

// main thread only
public class TabListUtil {

    private static final String HEADER = "Auslastung: ";
    private static final String FOOTER = "\n§fHandel: §a%d%% §8| §fTod: §a%d%% §8| §fTransfer: §a%d%%";


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
        ConfigManager config = ConfigManager.getManager();
        return Component.text(FOOTER.formatted(Math.round(config.getTradeTax()*100), Math.round(config.getDeathTax()*100), Math.round(config.getNetherTransferTax()*100)));
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
