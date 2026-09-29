package xyz.jupp.minecraft.listener;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.team.Teams;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.utils.ClaimedAreaHelper;

public class TeamAreaListener implements Listener {

    // README: the mob griefing part is in the MobLimiterListener

    private static boolean isPvPProtected(@NotNull Location location) {
        TeamCacheObject teamCacheObject = ClaimedAreaHelper.getClaimingTeam(location);
        return teamCacheObject != null
                && teamCacheObject.getLevel() >= Teams.PVP_LEVEL
                && !teamCacheObject.isZoneOptionPvP();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerAttackOther(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile proj &&
                proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null) return;
        if (isPvPProtected(victim.getLocation()) || isPvPProtected(attacker.getLocation())) {
            event.setCancelled(true);
            attacker.sendMessage(Main.getChatPrefix() + "PVP ist in diesem Team-Gebiet §cdeaktiviert§f!");
        }
    }



    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerInteractWithProtectedArea(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        TeamCacheObject teamCacheObject = clicked != null
                ? ClaimedAreaHelper.getClaimingTeam(clicked)
                : ClaimedAreaHelper.getClaimingTeam(event.getPlayer().getLocation());
        if (teamCacheObject == null || teamCacheObject.isZoneOptionInteract()) return;

        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(event.getPlayer());
        if (!teamCacheObject.getTeamID().equals(pco.getTeamID())) {
            event.setCancelled(true);
        }
    }

}
