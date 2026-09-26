package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AfkHelper {

    public static long afkSeconds = 600;
    public static long warnSeconds = 30;

    public static final Map<UUID, Long> lastActivityMs = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> warned = new ConcurrentHashMap<>();

    public static void initPlayer(Player p) {
        UUID id = p.getUniqueId();
        lastActivityMs.put(id, System.currentTimeMillis());
        warned.remove(id);
    }

    public static void removePlayer(UUID id) {
        lastActivityMs.remove(id);
        warned.remove(id);
    }

    public static void markActivity(Player p) {
        UUID id = p.getUniqueId();
        lastActivityMs.put(id, System.currentTimeMillis());
        warned.remove(id);
    }

    public static @NotNull BukkitRunnable tickKickTask() {
        return new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long kickAfterMs = afkSeconds * 1000L;
                long warnAtMs = (afkSeconds - warnSeconds) * 1000L;

                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();
                    long last = lastActivityMs.getOrDefault(id, now);
                    long idleMs = now - last;

                    if (warnSeconds > 0 && idleMs >= warnAtMs && idleMs < kickAfterMs) {
                        long remainingSec = (kickAfterMs - idleMs + 999) / 1000;

                        p.sendActionBar(
                                Component.text("Inaktiv: ", NamedTextColor.DARK_GRAY)
                                        .append(Component.text("Kick in ", NamedTextColor.RED))
                                        .append(Component.text(remainingSec + "s", NamedTextColor.YELLOW))
                                        .append(Component.text(" (bewegen um zu bleiben)", NamedTextColor.GRAY))
                        );

                        warned.put(id, true);
                        continue;
                    }

                    if (idleMs < warnAtMs) {
                        warned.remove(id);
                    }

                    if (idleMs >= kickAfterMs) {
                        removePlayer(id);
                        p.kick(
                                Component.text("KlotzscherPub", NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                                        .append(Component.newline())
                                        .append(Component.text("Du wurdest wegen Inaktivität gekickt.", NamedTextColor.RED))
                        );
                    }
                }

            }
        };
    }
}
