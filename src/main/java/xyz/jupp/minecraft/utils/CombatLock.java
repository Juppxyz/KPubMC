package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A hit between two players keeps both in combat for COMBAT_SECONDS after the last hit.
 * In combat there is no teleport and no ender chest, and leaving the server kills the player (CombatListener).
 * Main thread only.
 */
public final class CombatLock {

    public static final long COMBAT_SECONDS = 15;
    private static final long COMBAT_MS = COMBAT_SECONDS * 1000L;

    // until when the player is in combat, and the last opponent
    private record Fight(long untilMs, UUID opponent) {}

    private static final Map<UUID, Fight> fights = new HashMap<>();

    private CombatLock() {}

    public static void tag(@NotNull Player player, @NotNull Player opponent) {
        Fight before = fights.put(player.getUniqueId(), new Fight(System.currentTimeMillis() + COMBAT_MS, opponent.getUniqueId()));
        if (before == null) {
            player.sendMessage(Main.getChatPrefix() + "§cDu bist im Kampf! §fWer sich jetzt ausloggt, stirbt.");
        }
        showTimer(player, COMBAT_SECONDS);
    }

    public static boolean isInCombat(@NotNull Player player) {
        Fight fight = fights.get(player.getUniqueId());
        return fight != null && fight.untilMs() > System.currentTimeMillis();
    }

    // the last opponent, null if offline
    public static @Nullable Player lastOpponent(@NotNull Player player) {
        Fight fight = fights.get(player.getUniqueId());
        return fight == null ? null : Bukkit.getPlayer(fight.opponent());
    }

    public static void untag(@NotNull UUID uuid) {
        fights.remove(uuid);
    }

    /** True (with a message to the player) while in combat: for teleports and the ender chest. */
    public static boolean denies(@NotNull Player player) {
        Fight fight = fights.get(player.getUniqueId());
        long leftMs = fight == null ? 0 : fight.untilMs() - System.currentTimeMillis();
        if (leftMs <= 0) return false;
        player.sendMessage(Main.getChatPrefix() + "§cIm Kampf geht das nicht. §fWarte noch §c" + seconds(leftMs) + "s§f.");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        return true;
    }

    // every second: the remaining time in the action bar, the end of the fight once
    public static @NotNull BukkitRunnable tickTask() {
        return new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Fight>> iterator = fights.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<UUID, Fight> entry = iterator.next();
                    Player player = Bukkit.getPlayer(entry.getKey());
                    long leftMs = entry.getValue().untilMs() - now;
                    if (player == null || leftMs <= 0) {
                        iterator.remove();
                        if (player != null) player.sendActionBar(Text.of("§a✔ Du bist nicht mehr im Kampf"));
                        continue;
                    }
                    showTimer(player, seconds(leftMs));
                }
            }
        };
    }

    private static void showTimer(Player player, long seconds) {
        player.sendActionBar(Text.of("§c⚔ Im Kampf §8» §f" + seconds + "s §8(nicht ausloggen)"));
    }

    private static long seconds(long ms) {
        return (ms + 999) / 1000;
    }
}
