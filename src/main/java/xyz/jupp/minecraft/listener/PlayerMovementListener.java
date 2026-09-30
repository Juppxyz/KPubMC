package xyz.jupp.minecraft.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import xyz.jupp.minecraft.team.Relations;
import xyz.jupp.minecraft.team.TeamAreaEffects;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.ChunkCacheObject;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Text;

import java.util.HashMap;
import java.util.Map;
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

    // the team whose area a player stood in after the last chunk change (absent: wilderness); main thread only
    private final Map<UUID, String> areaOf = new HashMap<>();

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
        if (!sameChunk(from, to)) enteredChunk(player, to);
    }

    // a warp or another teleport into an area counts like walking in
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!sameChunk(e.getFrom(), e.getTo())) enteredChunk(e.getPlayer(), e.getTo());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        areaOf.remove(e.getPlayer().getUniqueId());
    }

    // a respawn moves the player without a move or teleport event: the next area counts as entered from outside
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        areaOf.remove(e.getPlayer().getUniqueId());
    }

    // chunk coordinates from the block coordinates, getChunk() would look the chunk up (and could load it)
    private static boolean sameChunk(Location from, Location to) {
        return from.getWorld().equals(to.getWorld()) && from.getBlockX() >> 4 == to.getBlockX() >> 4 && from.getBlockZ() >> 4 == to.getBlockZ() >> 4;
    }

    private void enteredChunk(Player player, Location to) {
        UUID uuid = player.getUniqueId();
        ChunkCacheObject claim = ChunkCache.getInstance().getClaim(to);
        if (claim == null || claim.getTeamID() == null) {
            if (areaOf.remove(uuid) != null) player.sendActionBar(WILDERNESS);
            return;
        }
        String previous = areaOf.put(uuid, claim.getTeamID());
        boolean fromOutside = !claim.getTeamID().equals(previous);

        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
        String playerTeamID = playerCacheObject.getTeamID();
        if (claim.getTeamID().equals(playerTeamID)) {
            // own area: only when coming from outside it
            if (fromOutside) player.sendActionBar(Text.of(playerCacheObject.getTeamColor() + OWN_TEAM_AREA));
            return;
        }
        TeamCacheObject area = CacheHandler.getInstance().getTeamCacheObject(claim.getTeamID());
        if (area == null) {
            player.sendActionBar(UNKNOWN_TEAM);
            return;
        }
        showForeignArea(player, area, playerTeamID);
        if (fromOutside) TeamAreaEffects.entered(player, area, playerTeamID, to);
    }

    private static void showForeignArea(Player player, TeamCacheObject area, String playerTeamID) {
        if (Relations.partners(playerTeamID, area.getTeamID())) {
            player.sendActionBar(Text.of("§fGebiet von §l" + area.getTeamName() + " §8§l| §a§l✦ Partner"));
            return;
        }
        if (Relations.atWar(playerTeamID, area.getTeamID())) {
            player.sendActionBar(Text.of("§fGebiet von §l" + area.getTeamName() + " §8§l| §c§l⚔ Krieg §8§l| §f§lPvP an"));
            return;
        }
        String zoneOptionPvP = area.isZoneOptionPvP() ? PVP_ON : PVP_OFF;
        String zoneOptionMobDamage = area.isZoneOptionMobDamage() ? MOBGRIEF_ON : MOBGRIEF_OFF;
        String zoneOptionInteract = area.isZoneOptionInteract() ? INTERACT_OPEN : INTERACT_LOCKED;
        player.sendActionBar(Text.of("§fGebiet von: §l" + area.getTeamName() + " §8§l| " +
                zoneOptionPvP + " §f§l- " + zoneOptionMobDamage + "§f§l- " + zoneOptionInteract + "§f§l- "));
    }

}
