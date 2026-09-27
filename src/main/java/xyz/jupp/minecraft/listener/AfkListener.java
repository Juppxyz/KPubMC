package xyz.jupp.minecraft.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import static xyz.jupp.minecraft.utils.AfkHelper.markActivity;
import static xyz.jupp.minecraft.utils.AfkHelper.removePlayer;

public class AfkListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        markActivity(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removePlayer(e.getPlayer().getUniqueId());
    }

    // only a block change counts, turning the head does not
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasExplicitlyChangedBlock()) return;
        markActivity(e.getPlayer());
    }

    // markActivity is thread-safe, so it runs directly on the chat thread
    @EventHandler
    public void onChat(AsyncChatEvent e) {
        markActivity(e.getPlayer());
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        markActivity(e.getPlayer());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) {
            markActivity(e.getPlayer());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            markActivity(p);
        }
    }
}
