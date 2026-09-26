package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import xyz.jupp.minecraft.Main;


import static xyz.jupp.minecraft.utils.AfkHelper.*;

public class AfkListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        initPlayer(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removePlayer(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;

        // Nur Blockwechsel zählt
        if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
            markActivity(e.getPlayer());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Bukkit.getScheduler().runTask(Main.getInstance(), () -> markActivity(e.getPlayer()));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        markActivity(e.getPlayer());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK
                || a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
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
