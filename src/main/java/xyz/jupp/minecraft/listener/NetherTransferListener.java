package xyz.jupp.minecraft.listener;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import xyz.jupp.minecraft.utils.EndAccess;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Tasks;

public class NetherTransferListener implements Listener {

    // players respawning or moved by a command right now: a respawn in the Nether (anchor) is no transfer, the death
    // was taxed already; an admin's /mvtp or /tp is no trip of the player's own
    private static final Set<UUID> exempt = ConcurrentHashMap.newKeySet();

    @EventHandler
    public void onEnterEnd(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        // the End opens some days after the season start
        if (event.getTo().getWorld() != null && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END && !EndAccess.isOpen()) {
            event.setCancelled(true);
            LocalDateTime unlock = EndAccess.unlock();
            long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(ZoneId.of("Europe/Berlin")), unlock.toLocalDate());
            player.sendMessage(Main.getChatPrefix() + "§cDas End ist noch gesperrt.§f Es öffnet am §e"
                    + unlock.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " §fum §e"
                    + unlock.format(DateTimeFormatter.ofPattern("HH:mm")) + " Uhr §7(noch " + daysLeft + (daysLeft == 1 ? " Tag)" : " Tage)"));
        }
    }

    // an ender pearl or a vehicle through an End portal would take its player along
    @EventHandler(ignoreCancelled = true)
    public void onEntityEnterEnd(EntityPortalEvent event) {
        if (EndAccess.isOpen() || event.getTo() == null || event.getTo().getWorld() == null) return;
        if (event.getTo().getWorld().getEnvironment() == World.Environment.THE_END) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        exemptThisTick(event.getPlayer().getUniqueId());
    }

    // the world change follows within the same teleport
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCommandTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND) exemptThisTick(event.getPlayer().getUniqueId());
    }

    private static void exemptThisTick(UUID uuid) {
        exempt.add(uuid);
        Tasks.syncLater(1L, () -> exempt.remove(uuid));
    }

    /**
     * Every way of a player's own from the overworld into the Nether costs the same: portal, warp (player or team warp),
     * an ender pearl thrown through a portal, a vehicle. Charged once the player has arrived, so a cancelled trip costs
     * nothing; respawns and command teleports (admins) are free.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnterNether(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (event.getFrom().getEnvironment() != World.Environment.NORMAL || player.getWorld().getEnvironment() != World.Environment.NETHER) return;
        if (exempt.remove(player.getUniqueId())) return;
        chargeNetherTax(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        exempt.remove(event.getPlayer().getUniqueId());
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
