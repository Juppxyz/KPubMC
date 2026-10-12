package xyz.jupp.minecraft.building;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import xyz.jupp.minecraft.utils.Tasks;

/**
 * Notices a finished copy of a template: every placed block that occurs in a template is a candidate for the last
 * block of a copy. The check runs a tick later, when the neighbours (stairs shapes, fences) have settled, and reads
 * only the few cells around the block, nothing more. No block that is broken is ever looked at here.
 */
public class BauamtListener implements Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Material material = block.getType();
        if (!Bauamt.isTrigger(material)) return;
        Player player = event.getPlayer();
        Tasks.sync(() -> {
            if (player.isOnline() && block.getType() == material) Bauamt.placed(player, block);
        });
    }

}
