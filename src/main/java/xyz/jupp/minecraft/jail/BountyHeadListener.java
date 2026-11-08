package xyz.jupp.minecraft.jail;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import xyz.jupp.minecraft.Main;

// a placed head loses what makes it a bounty (the block keeps no item data), so it stays an item
public class BountyHeadListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (Bounties.read(event.getItemInHand()) == null) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Main.getChatPrefix() + "Den Kopf will der §9Sheriff §fsehen, nicht deine Wand.");
    }

}
