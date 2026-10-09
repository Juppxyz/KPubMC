package xyz.jupp.minecraft.team;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.ChunkCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.commands.SpecCommand;

import java.util.HashMap;
import java.util.Map;

/**
 * What a team's own area does for it: haste for its members there (from HASTE_LEVEL, stronger from STRONG_HASTE_LEVEL)
 * and a live alarm to the online members when a stranger walks in (from ALARM_LEVEL, can be switched off). The alarm
 * is only a chat line, nothing is stored or logged. Main thread only.
 */
public final class TeamAreaEffects {

    private TeamAreaEffects() {}

    private static final long HASTE_PERIOD_TICKS = 40;
    private static final int HASTE_TICKS = 80;
    // one alarm per stranger and team in this time
    private static final long ALARM_COOLDOWN_MILLIS = 120_000;
    private static final Map<String, Long> lastAlarm = new HashMap<>();

    /** In onEnable: the haste every two seconds. */
    public static void startTask() {
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), TeamAreaEffects::applyHaste, 100L, HASTE_PERIOD_TICKS);
    }

    private static void applyHaste() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) continue;
            TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(player).getTeamCacheObject();
            if (team == null) continue;
            int amplifier = Teams.hasteAmplifier(team.getLevel());
            if (amplifier < 0) continue;
            ChunkCacheObject claim = ChunkCache.getInstance().getClaim(player.getLocation());
            if (claim == null || !team.getTeamID().equals(claim.getTeamID())) continue;
            PotionEffect current = player.getPotionEffect(PotionEffectType.HASTE);
            // a stronger or longer haste (beacon, potion) stays; a weaker one comes back when ours ends
            if (current != null && (current.getAmplifier() > amplifier
                    || (current.getAmplifier() == amplifier && (current.isInfinite() || current.getDuration() > HASTE_TICKS - HASTE_PERIOD_TICKS)))) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, HASTE_TICKS, amplifier, true, false, true));
        }
    }

    /** A player walked into a team's area from outside it: the team's online members get a line, strangers only. */
    public static void entered(@NotNull Player player, @NotNull TeamCacheObject area, @Nullable String playerTeamID, @NotNull Location at) {
        if (area.getLevel() < Teams.ALARM_LEVEL || !area.isZoneOptionAlarm()) return;
        if (area.getTeamID().equals(playerTeamID) || Relations.partners(area.getTeamID(), playerTeamID)) return;
        if (player.getGameMode() == GameMode.SPECTATOR || SpecCommand.getSpecMode(player.getUniqueId())) return;
        long now = System.currentTimeMillis();
        String key = area.getTeamID() + ":" + player.getUniqueId();
        Long last = lastAlarm.get(key);
        if (last != null && now - last < ALARM_COOLDOWN_MILLIS) return;
        lastAlarm.put(key, now);
        if (lastAlarm.size() > 500) lastAlarm.values().removeIf(time -> now - time >= ALARM_COOLDOWN_MILLIS);

        boolean enemy = Relations.atWar(area.getTeamID(), playerTeamID);
        String message = area.getTeamColor() + "Team-Alarm §8» " + (enemy ? "§c⚔ " : "§e") + player.getName()
                + " §fist in eurem Gebiet §8(" + at.getBlockX() + ", " + at.getBlockZ() + ")";
        for (Player member : Bukkit.getOnlinePlayers()) {
            if (member != player && area.getTeamID().equals(CacheHandler.getInstance().getPlayerInCache(member).getTeamID())) {
                member.sendMessage(message);
                member.playSound(member.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.2f);
            }
        }
    }

}
