package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.economy.Bankruptcy;
import xyz.jupp.minecraft.inventory.Items;
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
        if (Bankruptcy.isBroke()) {
            // how far the treasury is from the recovery: players see their emergency purchases arrive
            Bankruptcy.Level level = Bankruptcy.lastLevel();
            String progress = level == null ? "§cKasse leer"
                    : "§7Kasse §f" + Items.format(Math.max(0, level.free())) + " §7von §f" + Items.format(level.recoveredAt()) + " §7zur Erholung";
            return Component.text("\n§4§lSTAATSPLEITE §8| " + progress + "\n§7Steuern: " + Economy.levelWord()
                    + " §8| §7Notverkauf im Shop");
        }
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
