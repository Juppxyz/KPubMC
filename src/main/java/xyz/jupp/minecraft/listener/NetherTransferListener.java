package xyz.jupp.minecraft.listener;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.utils.Tasks;

public class NetherTransferListener implements Listener {

    @EventHandler
    public void onEnterNether(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) return;

        float transferTaxRate = ConfigManager.getManager().getNetherTransferTax();

        // database on a worker, the message on the main thread
        Tasks.supplyAsync(() -> {
            int money = PlayerCollection.getMoney(player);

            if (money <= 100) {
                return Main.getChatPrefix() + "Dir wurde §ckeine §fTransfer-Steuer berechnet.";
            }

            int tax = Math.round(money * transferTaxRate);
            PlayerCollection.addMoney(player, -tax);

            return String.format(
                    "%sDir wurden §a%s §8(§2%.0f%%§8) §fals Transfer-Steuer berechnet.",
                    Main.getChatPrefix(),
                    Main.getCurrencyName(tax),
                    transferTaxRate * 100
            );
        }, message -> player.sendMessage(message));
    }

}
