package xyz.jupp.minecraft.listener;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Tasks;

public class NetherTransferListener implements Listener {

    @EventHandler
    public void onEnterNether(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) return;

        // progressive tax on the balance, booked into the state treasury; database on a worker, the message on the main thread
        Tasks.supplyAsync(() -> {
            Taxes.BalanceTax tax = Taxes.chargeNetherTax(player.getUniqueId());
            if (tax.tax() == 0) {
                return Main.getChatPrefix() + "Dir wurde §ckeine §fTransfer-Steuer berechnet.";
            }
            return String.format(
                    "%sDir wurden §a%s §8(§2%.0f%%§8, gestaffelt) §fals Transfer-Steuer berechnet. §8→ Staatskasse",
                    Main.getChatPrefix(),
                    Main.getCurrencyName(tax.tax()),
                    tax.effectiveRate() * 100
            );
        }, message -> player.sendMessage(message));
    }

}
