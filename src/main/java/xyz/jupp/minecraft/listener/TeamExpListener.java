package xyz.jupp.minecraft.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import xyz.jupp.minecraft.team.Teams;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;

public class TeamExpListener implements Listener {

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();

        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        if (pco.getTeamID() == null) return;

        TeamCacheObject tco = pco.getTeamCacheObject();
        if (tco == null) return;

        int base = event.getAmount();
        if (base <= 0) return;

        double mult = Teams.xpMultiplier(tco.getLevel());
        int modified = (int) Math.max(1, Math.round(base * mult));

        event.setAmount(modified);
    }

}
