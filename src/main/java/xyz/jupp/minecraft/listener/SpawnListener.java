package xyz.jupp.minecraft.listener;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class SpawnListener implements Listener {

    @EventHandler
    public void blockLightnings(@NotNull LightningStrikeEvent event) {
        if (Locations.isLocationASpawn(event.getLightning().getLocation())) {
            event.getLightning().remove();
        }
    }


    @EventHandler
    public void onInteractAtSpawn(@NotNull PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (PermissionsUtil.isPlayerAdmin(player)) return;
        if (!Locations.isLocationASpawn(player.getLocation())) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() == Material.AIR) return;

        if (event.getAction().equals(Action.PHYSICAL)) {
            if (block.getType() == Material.STONE_PRESSURE_PLATE) return;
        }

        if (event.getAction().isRightClick()) {
            if (block.getType() == Material.BIRCH_WALL_SIGN) return;
            if (block.getType() == Material.STONE_BUTTON) return;
            if (block.getType() == Material.ENDER_CHEST) return;
            if (block.getType().name().contains("DOOR") && !block.getType().name().contains("TRAPDOOR")) return;
        }

        event.setCancelled(true);
    }

}
