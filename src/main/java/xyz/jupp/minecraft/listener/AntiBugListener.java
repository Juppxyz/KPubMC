package xyz.jupp.minecraft.listener;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import xyz.jupp.minecraft.Main;

public class AntiBugListener implements Listener {

    // Thanks to Fading_Eclipse and TwixFNA
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.SPAWNER) {
            event.setExpToDrop(0);
        }
    }


    //private static final Material DRAGON_SPAWN_EGG = Material.matchMaterial("ENDER_DRAGON_SPAWN_EGG");
    //@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    //public void onInteract(PlayerInteractEvent e) {
    //    final Player p = e.getPlayer();
//
    //    if (p.getWorld().getEnvironment() != World.Environment.THE_END) return;
    //    if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    //    if (e.getHand() != EquipmentSlot.HAND) return;
//
    //    final Block clicked = e.getClickedBlock();
    //    if (clicked == null || clicked.getType() != Material.SPAWNER) return;
//
    //    final ItemStack item = e.getItem();
    //    if (item == null) return;
    //    if (DRAGON_SPAWN_EGG == null) return;
    //    if (item.getType() != DRAGON_SPAWN_EGG) return;
//
    //    e.setCancelled(true);
    //    e.setUseInteractedBlock(Event.Result.DENY);
    //    e.setUseItemInHand(Event.Result.DENY);
//
    //    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
    //    p.sendMessage(Main.getChatPrefix() + "§cDu kannst keinen Spawner mit einem Enderdrachen-Spawn-Ei in der Overworld erschaffen.");
    //}


}