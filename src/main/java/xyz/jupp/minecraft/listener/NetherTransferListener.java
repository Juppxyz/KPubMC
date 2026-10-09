package xyz.jupp.minecraft.listener;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import xyz.jupp.minecraft.utils.EndAccess;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Tasks;

public class NetherTransferListener implements Listener {

    @EventHandler
    public void onEnterNether(PlayerPortalEvent event) {
        Player player = event.getPlayer();

        // the End opens some days after the season start
        if (event.getTo() != null && event.getTo().getWorld() != null
                && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END && !EndAccess.isOpen()) {
            event.setCancelled(true);
            LocalDateTime unlock = EndAccess.unlock();
            long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(ZoneId.of("Europe/Berlin")), unlock.toLocalDate());
            player.sendMessage(Main.getChatPrefix() + "§cDas End ist noch gesperrt.§f Es öffnet am §e"
                    + unlock.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " §fum §e"
                    + unlock.format(DateTimeFormatter.ofPattern("HH:mm")) + " Uhr §8(noch " + daysLeft + (daysLeft == 1 ? " Tag)" : " Tage)"));
            return;
        }
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL
                || event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        chargeNetherTax(player);
    }

    // a warp into the Nether (player or team warp) costs the same as the portal; PlayerPortalEvent has its own handler list
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onWarpToNether(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        World from = event.getFrom().getWorld();
        World to = event.getTo().getWorld();
        if (from == null || to == null || from.getEnvironment() != World.Environment.NORMAL || to.getEnvironment() != World.Environment.NETHER) return;
        chargeNetherTax(event.getPlayer());
    }

    // progressive tax on the balance, booked into the state treasury; database on a worker, the message on the main thread
    private static void chargeNetherTax(Player player) {
        Tasks.supplyAsync(() -> {
            Taxes.BalanceTax tax = Taxes.chargeNetherTax(player.getUniqueId());
            if (tax.tax() == 0) {
                return Main.getChatPrefix() + "Dir wurde §ckeine §fNether-Steuer berechnet.";
            }
            return String.format(
                    "%sDir wurden §a%s §fNether-Steuer berechnet.",
                    Main.getChatPrefix(),
                    Main.getCurrencyName(tax.tax())
            );
        }, message -> player.sendMessage(message));
    }

}
