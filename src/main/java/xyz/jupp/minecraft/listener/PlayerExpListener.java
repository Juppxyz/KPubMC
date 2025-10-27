package xyz.jupp.minecraft.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;

public class PlayerExpListener implements Listener {

    @EventHandler
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
        if (playerCacheObject.getTeamID() == null) return;
        TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();

        int amount = event.getAmount();
        int teamLevel = teamCacheObject.getLevel();
        if (teamLevel == 1) {
            amount = (int) (amount + (amount * 0.10));
        }else if (teamLevel == 2) {
            amount = (int) (amount + (amount * 0.25));
        } else if (teamLevel == 5) {
            amount = (int) (amount + (amount * 0.50));
        }


        player.setExp(player.getExp() + amount);
    }


}
