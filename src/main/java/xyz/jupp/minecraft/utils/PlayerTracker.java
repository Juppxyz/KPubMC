package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.commands.SpecCommand;
import xyz.jupp.minecraft.items.TrackerCompass;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The tracker compass (black market): points to the nearest other player for a few minutes.
 * One task every half second only looks at the players with an active compass (usually none) and the players of their
 * world. The needle is set per player with a spawn packet, so the item never changes and nothing is scanned otherwise.
 * Main thread only.
 */
public final class PlayerTracker {

    private static final long PERIOD_TICKS = 10;
    private static final double NEAR = 15;
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    // player -> end of the tracking in millis
    private static final Map<UUID, Long> active = new HashMap<>();

    private PlayerTracker() {}

    public static void startTask() {
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), PlayerTracker::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    public static void start(@NotNull Player player, long millis) {
        active.put(player.getUniqueId(), System.currentTimeMillis() + millis);
    }

    /** Seconds left, 0 if the compass is not active. */
    public static long secondsLeft(@NotNull Player player) {
        Long end = active.get(player.getUniqueId());
        return end == null ? 0 : Math.max(0, (end - System.currentTimeMillis()) / 1000);
    }

    private static void tick() {
        if (active.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                // left the server: the needle is reset by the next join anyway
                iterator.remove();
                continue;
            }
            if (now >= entry.getValue()) {
                iterator.remove();
                player.setCompassTarget(Bukkit.getWorlds().getFirst().getSpawnLocation());
                player.sendActionBar(Text.section("§7Der Spürkompass ist wieder still."));
                continue;
            }
            Player target = nearest(player);
            boolean holding = isTracker(player.getInventory().getItemInMainHand()) || isTracker(player.getInventory().getItemInOffHand());
            if (target == null) {
                if (holding) player.sendActionBar(Text.section("§7Der Spürkompass findet in dieser Welt niemanden."));
                continue;
            }
            player.setCompassTarget(target.getLocation());
            if (holding) player.sendActionBar(Text.section(hint(player.getLocation(), target.getLocation())));
        }
    }

    // the nearest visible player of the same world, not in the own team and not an admin in spec mode
    private static @Nullable Player nearest(Player player) {
        String team = CacheHandler.getInstance().getPlayerInCache(player).getTeamID();
        Location from = player.getLocation();
        Location scratch = new Location(null, 0, 0, 0);
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player other : player.getWorld().getPlayers()) {
            if (other == player || other.getGameMode() == GameMode.SPECTATOR || !player.canSee(other)) continue;
            if (SpecCommand.getSpecMode(other.getUniqueId())) continue;
            if (team != null && team.equals(CacheHandler.getInstance().getPlayerInCache(other).getTeamID())) continue;
            double distance = other.getLocation(scratch).distanceSquared(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    // direction relative to the view (Minecraft yaw: 0 = south, clockwise) and a rounded distance, no name
    private static String hint(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double distance = from.distance(to);
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double turn = ((targetYaw - from.getYaw()) % 360 + 540) % 360 - 180;
        String arrow = ARROWS[Math.floorMod(Math.round(turn / 45), 8)];
        if (distance < NEAR) return "§b" + arrow + " §fJemand ist ganz in deiner Nähe";
        return "§b" + arrow + " §fJemand ist etwa §b" + Math.round(distance / 10) * 10 + " Blöcke §fentfernt";
    }

    private static boolean isTracker(@Nullable ItemStack item) {
        return item != null && !item.getType().isAir() && item.getPersistentDataContainer().has(TrackerCompass.KEY, PersistentDataType.BYTE);
    }

}
