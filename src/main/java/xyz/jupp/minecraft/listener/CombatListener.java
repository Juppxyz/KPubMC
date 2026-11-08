package xyz.jupp.minecraft.listener;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.Vault;
import xyz.jupp.minecraft.utils.CombatLock;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Text;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Combat log: leaving the server in combat (CombatLock) kills the player, the last opponent gets the kill.
public class CombatListener implements Listener {

    // killed for leaving in combat, while the death event runs; main thread only
    private final Set<UUID> loggingOut = new HashSet<>();
    // kicked for something the player did (spam, bad packets, a second login on the account): counts like leaving
    private final Set<UUID> selfKicked = new HashSet<>();

    private static final Set<PlayerKickEvent.Cause> PLAYER_CAUSED = EnumSet.of(
            PlayerKickEvent.Cause.DUPLICATE_LOGIN, PlayerKickEvent.Cause.SPAM, PlayerKickEvent.Cause.ILLEGAL_ACTION,
            PlayerKickEvent.Cause.ILLEGAL_CHARACTERS, PlayerKickEvent.Cause.FLYING_PLAYER, PlayerKickEvent.Cause.FLYING_VEHICLE,
            PlayerKickEvent.Cause.INVALID_VEHICLE_MOVEMENT, PlayerKickEvent.Cause.INVALID_PLAYER_MOVEMENT,
            PlayerKickEvent.Cause.INVALID_ENTITY_ATTACKED, PlayerKickEvent.Cause.INVALID_PAYLOAD, PlayerKickEvent.Cause.INVALID_COOKIE,
            PlayerKickEvent.Cause.OUT_OF_ORDER_CHAT, PlayerKickEvent.Cause.UNSIGNED_CHAT, PlayerKickEvent.Cause.CHAT_VALIDATION_FAILED,
            PlayerKickEvent.Cause.EXPIRED_PROFILE_PUBLIC_KEY, PlayerKickEvent.Cause.INVALID_PUBLIC_KEY_SIGNATURE,
            PlayerKickEvent.Cause.TOO_MANY_PENDING_CHATS, PlayerKickEvent.Cause.SELF_INTERACTION,
            PlayerKickEvent.Cause.RESOURCE_PACK_REJECTION);

    // admins in creative or spectator do not fight, the arena keeps the inventory anyway
    private static boolean canFight(Player player) {
        GameMode mode = player.getGameMode();
        return (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !DeathListener.isPlayerInArena(player);
    }

    // MONITOR: only a hit that really lands starts a fight, not one a protected team area or a partnership cancelled
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = TeamAreaListener.responsiblePlayer(event);
        // own arrows or TNT are no fight, neither are snowballs and eggs (no damage)
        if (attacker == null || attacker == victim || !attacker.isOnline() || event.getDamage() <= 0) return;
        if (!canFight(victim) || !canFight(attacker)) return;
        // a caught prisoner is out of every fight (PrisonListener cancels their damage, this is the safety net)
        if (JailHandler.isCaughtPrisoner(victim) || JailHandler.isCaughtPrisoner(attacker)) return;

        CombatLock.tag(victim, attacker);
        CombatLock.tag(attacker, victim);
        // no stashing the gear in the ender chest mid-fight
        closeEnderChest(victim);
        closeEnderChest(attacker);
    }

    // the ender chest and Basil's vault (Schließfach): no stashing the gear mid-fight
    private static void closeEnderChest(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.ENDER_CHEST || top.getHolder(false) instanceof Vault) player.closeInventory();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        if (PLAYER_CAUSED.contains(event.getCause())) selfKicked.add(event.getPlayer().getUniqueId());
    }

    // LOWEST: the death runs before the other quit handlers clean up (cache, spec mode)
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean selfCaused = selfKicked.remove(player.getUniqueId());
        boolean inCombat = CombatLock.isInCombat(player);
        Player opponent = CombatLock.lastOpponent(player);
        CombatLock.untag(player.getUniqueId());
        if (!inCombat || player.isDead()) return;
        // a caught prisoner cannot die in the cell, not even by a fight tagged just before the arrest
        if (JailHandler.isCaughtPrisoner(player)) return;
        // a kick by the server (AFK, admin, restart) is not the player's choice; a lost connection counts (a cable can
        // be pulled on purpose), and so does a kick the player brought about (spam, bad packets, a second login)
        PlayerQuitEvent.QuitReason reason = event.getReason();
        if (reason != PlayerQuitEvent.QuitReason.DISCONNECTED && reason != PlayerQuitEvent.QuitReason.TIMED_OUT && !selfCaused) return;

        // like a kill by the opponent: drops, death tax, team points, bounty (DeathListener)
        if (opponent != null) player.setKiller(opponent);
        loggingOut.add(player.getUniqueId());
        try {
            // the death event runs right inside setHealth
            player.setHealth(0);
        } finally {
            loggingOut.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        CombatLock.untag(player.getUniqueId());
        if (loggingOut.contains(player.getUniqueId())) {
            event.deathMessage(Text.section(Main.getChatPrefix() + "§c" + player.getName() + " §fhat sich im Kampf ausgeloggt und ist gestorben."));
        }
    }
}
