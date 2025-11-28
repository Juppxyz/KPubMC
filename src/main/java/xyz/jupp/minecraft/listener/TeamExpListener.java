package xyz.jupp.minecraft.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;

import java.util.Map;

public class TeamExpListener implements Listener {

    private static final Map<Integer, Double> LEVEL_MULT = Map.of(
            1, 1.10,
            2, 1.25,
            3, 1.35,
            4, 1.50,
            5, 2.0
    );

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();

        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        if (pco == null || pco.getTeamID() == null) return;

        TeamCacheObject tco = pco.getTeamCacheObject();
        if (tco == null) return;

        int base = event.getAmount();
        if (base <= 0) return;

        double mult = LEVEL_MULT.getOrDefault(tco.getLevel(), 1.0);
        int modified = (int) Math.max(1, Math.round(base * mult));

        event.setAmount(modified);
    }


    @EventHandler(priority = EventPriority.NORMAL)
    public void test(BlockBreakEvent event) {


    }

}
