package xyz.jupp.minecraft.listener;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.CombatLock;
import xyz.jupp.minecraft.utils.Text;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Combat log: leaving the server in combat (CombatLock) kills the player, the last opponent gets the kill.
public class CombatListener implements Listener {

    // killed for leaving in combat, while the death event runs; main thread only
    private final Set<UUID> loggingOut = new HashSet<>();

    // the player behind the damage: melee, projectiles, TNT, crystals and potions (Paper's damage source), a tamed animal's owner
    private static @Nullable Player responsiblePlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamageSource().getCausingEntity() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (event.getDamager() instanceof Tameable tameable && tameable.getOwner() instanceof Player player) return player;
        return null;
    }

    // admins in creative or spectator do not fight, the arena keeps the inventory anyway
    private static boolean canFight(Player player) {
        GameMode mode = player.getGameMode();
        return (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !DeathListener.isPlayerInArena(player);
    }

    // MONITOR: only a hit that really lands starts a fight, not one a protected team area cancelled
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = responsiblePlayer(event);
        // own arrows or TNT are no fight, neither are snowballs and eggs (no damage)
        if (attacker == null || attacker == victim || !attacker.isOnline() || event.getDamage() <= 0) return;
        if (!canFight(victim) || !canFight(attacker)) return;

        CombatLock.tag(victim, attacker);
        CombatLock.tag(attacker, victim);
        // no stashing the gear in the ender chest mid-fight
        closeEnderChest(victim);
        closeEnderChest(attacker);
    }

    private static void closeEnderChest(Player player) {
        if (player.getOpenInventory().getTopInventory().getType() == InventoryType.ENDER_CHEST) player.closeInventory();
    }

    // LOWEST: the death runs before the other quit handlers clean up (cache, spec mode)
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean inCombat = CombatLock.isInCombat(player);
        Player opponent = CombatLock.lastOpponent(player);
        CombatLock.untag(player.getUniqueId());
        if (!inCombat || player.isDead()) return;
        // a kick (AFK, admin, server stop) is not the player's choice; a lost connection counts, a cable can be pulled on purpose
        PlayerQuitEvent.QuitReason reason = event.getReason();
        if (reason != PlayerQuitEvent.QuitReason.DISCONNECTED && reason != PlayerQuitEvent.QuitReason.TIMED_OUT) return;

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
