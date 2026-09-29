package xyz.jupp.minecraft.listener;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.utils.Text;
import xyz.jupp.minecraft.team.Relations;
import xyz.jupp.minecraft.team.Teams;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.utils.ClaimedAreaHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TeamAreaListener implements Listener {

    // README: the mob griefing part is in the MobLimiterListener

    private static boolean isPvPProtected(@NotNull Location location) {
        TeamCacheObject teamCacheObject = ClaimedAreaHelper.getClaimingTeam(location);
        return teamCacheObject != null
                && teamCacheObject.getLevel() >= Teams.PVP_LEVEL
                && !teamCacheObject.isZoneOptionPvP();
    }

    // the player behind the damage: melee, projectiles, TNT, crystals and potion clouds (Paper's damage source), a tamed animal's owner
    private static @Nullable Player responsiblePlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamageSource().getCausingEntity() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (event.getDamager() instanceof Tameable tameable && tameable.getOwner() instanceof Player player) return player;
        return null;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerAttackOther(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = responsiblePlayer(event);
        // own TNT or arrows are no PvP
        if (attacker == null || attacker == victim) return;
        // a shooter who left meanwhile is not in the cache any more (it would load on the main thread)
        if (attacker.isOnline()) {
            String attackerTeam = CacheHandler.getInstance().getPlayerInCache(attacker).getTeamID();
            String victimTeam = CacheHandler.getInstance().getPlayerInCache(victim).getTeamID();
            if (Relations.partners(attackerTeam, victimTeam)) {
                event.setCancelled(true);
                attacker.sendActionBar(Text.of("§a✦ Eure Teams sind Partner"));
                return;
            }
            // war: no area protects them from each other
            if (Relations.atWar(attackerTeam, victimTeam)) return;
        }
        if (isPvPProtected(victim.getLocation()) || isPvPProtected(attacker.getLocation())) {
            event.setCancelled(true);
            attacker.sendMessage(Main.getChatPrefix() + "PVP ist in diesem Team-Gebiet §cdeaktiviert§f!");
        }
    }



    // harmful splash potions leave the thrower's partners alone
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player thrower) || !harmful(event.getPotion().getEffects())) return;
        for (LivingEntity target : event.getAffectedEntities()) {
            if (target instanceof Player player && player != thrower && partners(thrower, player)) event.setIntensity(target, 0);
        }
    }

    // the same for lingering clouds
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onCloudApply(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player thrower)) return;
        List<PotionEffect> effects = new ArrayList<>(cloud.getCustomEffects());
        PotionType base = cloud.getBasePotionType();
        if (base != null) effects.addAll(base.getPotionEffects());
        if (!harmful(effects)) return;
        event.getAffectedEntities().removeIf(target -> target instanceof Player player && player != thrower && partners(thrower, player));
    }

    private static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) return true;
        }
        return false;
    }

    private static boolean partners(Player one, Player other) {
        if (!one.isOnline() || !other.isOnline()) return false;
        return Relations.partners(CacheHandler.getInstance().getPlayerInCache(one).getTeamID(), CacheHandler.getInstance().getPlayerInCache(other).getTeamID());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerInteractWithProtectedArea(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        TeamCacheObject teamCacheObject = clicked != null
                ? ClaimedAreaHelper.getClaimingTeam(clicked)
                : ClaimedAreaHelper.getClaimingTeam(event.getPlayer().getLocation());
        if (teamCacheObject == null || teamCacheObject.isZoneOptionInteract()) return;

        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(event.getPlayer());
        // partners share their areas
        if (!teamCacheObject.getTeamID().equals(pco.getTeamID()) && !Relations.partners(teamCacheObject.getTeamID(), pco.getTeamID())) {
            event.setCancelled(true);
        }
    }

}
