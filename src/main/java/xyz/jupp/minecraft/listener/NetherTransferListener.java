package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.PlayerCollection;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public class NetherTransferListener implements Listener {

    private static final LocalDateTime END_UNLOCK_DATE =
            LocalDateTime.of(2025, 12, 10, 12, 0);


    @EventHandler
    public void onEnterNether(PlayerPortalEvent event) {
        Player player = event.getPlayer();

        // 1. Prüfen: geht es ins End?
        if (event.getTo() != null && event.getTo().getWorld() != null
                && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END) {

            LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Berlin"));

            if (now.isBefore(END_UNLOCK_DATE)) {
                event.setCancelled(true);

                long daysLeft = ChronoUnit.DAYS.between(
                        now.toLocalDate(),
                        END_UNLOCK_DATE.toLocalDate()
                );

                player.sendMessage(
                        Main.getChatPrefix() +
                                "§cDas End ist noch gesperrt.§f Es öffnet am §e" +
                                END_UNLOCK_DATE.toLocalDate() + " §fum §e" +
                                END_UNLOCK_DATE.toLocalTime() + "§f."
                );

                if (daysLeft > 0) {
                    player.sendMessage("§8Noch ca. §e" + daysLeft + " §8Tag(e) bis zur Öffnung.");
                }

                return;
            }
        }

        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) return;

        float transferTaxRate = ConfigManager.getManager().getNetherTransferTax();

        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            PlayerCollection playerCollection = new PlayerCollection(player);
            int money = playerCollection.getMoney();

            if (money <= 100) {
                // ⚠ player.sendMessage() eigentlich nur im Main-Thread
                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                    player.sendMessage(Main.getChatPrefix() + "Dir wurde §ckeine §fTransfer-Steuer berechnet.");
                });
                return;
            }

            int tax = Math.round(money * transferTaxRate);
            int updatedMoney = money - tax;
            playerCollection.updateMoney(updatedMoney);

            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                player.sendMessage(String.format(
                        "%sDir wurden §a%s §8(§2%.0f%%§8) §fals Transfer-Steuer berechnet.",
                        Main.getChatPrefix(),
                        Main.getCurrencyName(tax),
                        transferTaxRate * 100
                ));
            });
        });
    }

}
