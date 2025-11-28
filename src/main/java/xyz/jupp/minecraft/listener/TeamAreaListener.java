package xyz.jupp.minecraft.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.*;

public class TeamAreaListener implements Listener {

    // README: the mob griefing part is in the MobLimiterListener

    private boolean isProtected(@NotNull Location location, int neededLevel) {
        ChunkCacheObject chunkCacheObject = ChunkCache.getInstance().getChunkObject(location.getWorld().getName(), location.getChunk().getX(), location.getChunk().getZ());
        if (chunkCacheObject == null) return false;

        TeamCacheObject teamCacheObject = CacheHandler.getInstance().getTeamCacheObject(chunkCacheObject.getTeamID());
        if (teamCacheObject == null) return false;
        boolean checkTeamLevel = teamCacheObject.getLevel() >= neededLevel ;
        return checkTeamLevel && !teamCacheObject.isZoneOptionPvP();
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
        if (isProtected(victim.getLocation(), 3) || isProtected(attacker.getLocation(), 3)) {
            event.setCancelled(true);
            attacker.sendMessage(Main.getChatPrefix() + "PVP ist in diesem Team-Gebiet §cdeaktiviert§f!");
        }
    }



    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerInteractWithProtectedArea(PlayerInteractEvent event) {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(event.getPlayer());
        if (pco == null) return;

        Location location;
        if (event.getClickedBlock() != null) {
            location = event.getClickedBlock().getLocation();
        } else {
            location = event.getPlayer().getLocation();
        }

        ChunkCacheObject chunkCacheObject = ChunkCache.getInstance().getChunkObject(location.getWorld().getName(), location.getChunk().getX(), location.getChunk().getZ());
        if (chunkCacheObject == null) return;

        TeamCacheObject teamCacheObject = CacheHandler.getInstance().getTeamCacheObject(chunkCacheObject.getTeamID());
        if (teamCacheObject == null) return;
        if (teamCacheObject.getTeamID().equals(pco.getTeamID())) return;
        else if (!teamCacheObject.getTeamID().equals(pco.getTeamID()) && !teamCacheObject.isZoneOptionInteract())
            event.setCancelled(true);
    }

}
