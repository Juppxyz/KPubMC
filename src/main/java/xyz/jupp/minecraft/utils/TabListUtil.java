package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.config.ConfigManager;

public class TabListUtil {

    private static final String header = "Auslastung: ";
    private static final String footer = "\n§fHandel: §a%d%% §8| §fTod: §a%d%% §8| §fTransfer: §a%d%%";


    public static void updateTabForAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPlayerListHeaderAndFooter(
                    Component.text(header + currentPerformance() + "\n"),
                    Component.text(footer.formatted(Math.round(ConfigManager.getManager().getTradeTax()*100), Math.round(ConfigManager.getManager().getDeathTax()*100), Math.round(ConfigManager.getManager().getNetherTransferTax()*100)))
            );
        }
    }

    public static void updateTabFor(Player player) {
        player.sendPlayerListHeaderAndFooter(
                Component.text(header + currentPerformance() + "\n"),
                Component.text(footer.formatted(Math.round(ConfigManager.getManager().getTradeTax()*100), Math.round(ConfigManager.getManager().getDeathTax()*100), Math.round(ConfigManager.getManager().getNetherTransferTax()*100)))
        );
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
