package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.PlayerCollection;

public class NetherTransferListener implements Listener {

    @EventHandler
    public void onEnterNether(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        float transferTaxRate = ConfigManager.getManager().getNetherTransferTax();
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) return;

        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            PlayerCollection playerCollection = new PlayerCollection(player);
            int money = playerCollection.getMoney();
            if (money <= 100) {
                player.sendMessage(Main.getChatPrefix() + "Dir wurde §ckeine §fTransfer-Steuer berechnet.");
                return;
            }

            int tax = Math.round(money * transferTaxRate);
            int updatedMoney = money - tax;
            playerCollection.updateMoney(updatedMoney);

            player.sendMessage(String.format(
                    "%sDir wurden §a%s §8(§2%.0f%%§8) §fals Transfer-Steuer berechnet.",
                    Main.getChatPrefix(),
                    Main.getCurrencyName(tax),
                    transferTaxRate * 100
            ));
        });
    }

}
