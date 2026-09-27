package xyz.jupp.minecraft.listener;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class AntiBugListener implements Listener {

    // Thanks to Fading_Eclipse and TwixFNA
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.SPAWNER) {
            event.setExpToDrop(0);
        }
    }

}
