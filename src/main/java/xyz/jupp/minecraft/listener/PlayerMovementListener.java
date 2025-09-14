package xyz.jupp.minecraft.listener;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import xyz.jupp.minecraft.cache.*;
import xyz.jupp.minecraft.utils.ClaimedAreaHelper;


public class PlayerMovementListener implements Listener {

    private static final String WILDERNESS = "§a§lDu bist nun wieder in der Wildnis";
    private static final String OWN_TEAM_AREA = "§lDu bist nun in deinem Team-Gebiet";
    private static final String PVP_ON = "§a§lPVP";
    private static final String PVP_OFF = "§c§lPVP";
    private static final String MOBGRIEF_ON = "§a§lMob-Griefing";
    private static final String MOBGRIEF_OFF = "§c§lMob-Griefing";
    private static final String INTERACT_ON = "§a§lGesichert";
    private static final String INTERACT_OFF = "§c§lOffen";

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getChunk().equals(e.getTo().getChunk())) return;

        Player player = e.getPlayer();
        Chunk newChunk = e.getTo().getChunk();
        Location currentLocation = player.getLocation();
        String playerUUID = player.getUniqueId().toString();

        ChunkCacheObject chunkCacheObject = ChunkCache.getInstance().getChunkObject(currentLocation.getWorld().getName(), newChunk.getX(), newChunk.getZ());
        if (chunkCacheObject == null) {
            if (ClaimedAreaHelper.getPlayersInClaimedAreas().remove(playerUUID) |
                    ClaimedAreaHelper.getTeamPlayerInArea().remove(playerUUID)) {
                player.sendActionBar(WILDERNESS);
            }
            return;
        }

        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
        String playerTeamID = playerCacheObject.getTeamID();

        // TeamID-NullCheck vor jeglicher Nutzung
        if (playerTeamID == null) {
            handleForeignClaim(player, playerUUID, chunkCacheObject);
            return;
        }

        boolean isOwnTeamArea = playerTeamID.equals(chunkCacheObject.getTeamID());
        if (isOwnTeamArea) {
            // Betritt eigenes Gebiet
            if (ClaimedAreaHelper.getTeamPlayerInArea().add(playerUUID)) {
                player.sendActionBar(playerCacheObject.getTeamColor() + OWN_TEAM_AREA);
            }
        } else {
            handleForeignClaim(player, playerUUID, chunkCacheObject);
        }
    }

    // Helper-Methoden für Übersicht
    private void handleForeignClaim(Player player, String playerUUID, ChunkCacheObject chunkCacheObject) {
        TeamCacheObject teamCacheObject = CacheHandler.getInstance().getTeamCacheObject(chunkCacheObject.getTeamID());
        if (teamCacheObject == null) {
            player.sendActionBar("§c§lDatenfehler: Unbekanntes Team!");
            return;
        }
        String zoneOptionPvP = teamCacheObject.isZoneOptionPvP() ? PVP_ON : PVP_OFF;
        String zoneOptionMobDamage = teamCacheObject.isZoneOptionMobDamage() ? MOBGRIEF_ON : MOBGRIEF_OFF;
        String zoneOptionInteract = teamCacheObject.isZoneOptionInteract() ? INTERACT_ON : INTERACT_OFF;
        ClaimedAreaHelper.getPlayersInClaimedAreas().add(playerUUID);
        player.sendActionBar("§fGebiet von: §l" + teamCacheObject.getTeamName() + " §8§l| " +
                zoneOptionPvP + " §f§l- " + zoneOptionMobDamage + "§f§l- " + zoneOptionInteract + "§f§l- ");
    }


}