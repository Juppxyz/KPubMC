package xyz.jupp.minecraft.listener;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class AntiBugListener implements Listener {

    // bought spawn eggs must not turn spawners into farms of that mob (vanilla allows it in survival)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnEggOnSpawner(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Material block = event.getClickedBlock().getType();
        if (block != Material.SPAWNER && block != Material.TRIAL_SPAWNER) return;
        ItemStack item = event.getItem();
        if (item == null || !item.getType().name().endsWith("_SPAWN_EGG")) return;
        if (PermissionsUtil.isPlayerAdmin(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Main.getChatPrefix() + "§fSpawner lassen sich mit Spawn-Eiern nicht umstellen.");
    }

    // Thanks to Fading_Eclipse and TwixFNA
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.SPAWNER) {
            event.setExpToDrop(0);
        }
    }

}
