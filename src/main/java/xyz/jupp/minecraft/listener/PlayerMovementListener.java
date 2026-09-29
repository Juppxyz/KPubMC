package xyz.jupp.minecraft.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.ChunkCacheObject;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Text;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


public class PlayerMovementListener implements Listener {

    private static final Component WILDERNESS = Text.of("§a§lDu bist nun wieder in der Wildnis");
    private static final Component UNKNOWN_TEAM = Text.of("§c§lDatenfehler: Unbekanntes Team!");
    private static final String OWN_TEAM_AREA = "§lDu bist nun in deinem Team-Gebiet";
    private static final String PVP_ON = "§a§lPVP";
    private static final String PVP_OFF = "§c§lPVP";
    private static final String MOBGRIEF_ON = "§a§lMob-Griefing";
    private static final String MOBGRIEF_OFF = "§c§lMob-Griefing";
    // interaction option on: strangers may use things there
    private static final String INTERACT_OPEN = "§a§lOffen";
    private static final String INTERACT_LOCKED = "§c§lGesichert";

    // players whose last chunk change ended in a claimed chunk (own or foreign); main thread only
    private final Set<UUID> playersInClaimedAreas = new HashSet<>();

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        // turning the head only: nothing to do
        if (!e.hasChangedBlock()) return;

        Player player = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();

        // Entity#isOnGround: Player only re-declares the same method as deprecated
        if (e.hasExplicitlyChangedBlock() && JailHandler.handlePossibleEscape(player) && ((Entity) player).isOnGround()) {
            e.setCancelled(true);
        }

        // chunk coordinates from the block coordinates, getChunk() would look the chunk up (and could load it)
        int chunkX = to.getBlockX() >> 4;
        int chunkZ = to.getBlockZ() >> 4;
        if (chunkX == from.getBlockX() >> 4 && chunkZ == from.getBlockZ() >> 4 && from.getWorld().equals(to.getWorld())) return;

        UUID playerUUID = player.getUniqueId();
        ChunkCacheObject chunkCacheObject = ChunkCache.getInstance().getClaim(player.getWorld(), chunkX, chunkZ);
        if (chunkCacheObject == null) {
            if (playersInClaimedAreas.remove(playerUUID)) {
                player.sendActionBar(WILDERNESS);
            }
            return;
        }

        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
        String playerTeamID = playerCacheObject.getTeamID();

        if (playerTeamID != null && playerTeamID.equals(chunkCacheObject.getTeamID())) {
            // Betritt eigenes Gebiet (nur aus der Wildnis, der Zustand gilt für eigene und fremde Gebiete)
            if (playersInClaimedAreas.add(playerUUID)) {
                player.sendActionBar(Text.of(playerCacheObject.getTeamColor() + OWN_TEAM_AREA));
            }
        } else {
            handleForeignClaim(player, playerUUID, chunkCacheObject);
        }
    }

    private void handleForeignClaim(Player player, UUID playerUUID, ChunkCacheObject chunkCacheObject) {
        TeamCacheObject teamCacheObject = CacheHandler.getInstance().getTeamCacheObject(chunkCacheObject.getTeamID());
        if (teamCacheObject == null) {
            player.sendActionBar(UNKNOWN_TEAM);
            return;
        }
        String zoneOptionPvP = teamCacheObject.isZoneOptionPvP() ? PVP_ON : PVP_OFF;
        String zoneOptionMobDamage = teamCacheObject.isZoneOptionMobDamage() ? MOBGRIEF_ON : MOBGRIEF_OFF;
        String zoneOptionInteract = teamCacheObject.isZoneOptionInteract() ? INTERACT_OPEN : INTERACT_LOCKED;
        playersInClaimedAreas.add(playerUUID);
        player.sendActionBar(Text.of("§fGebiet von: §l" + teamCacheObject.getTeamName() + " §8§l| " +
                zoneOptionPvP + " §f§l- " + zoneOptionMobDamage + "§f§l- " + zoneOptionInteract + "§f§l- "));
    }


}
