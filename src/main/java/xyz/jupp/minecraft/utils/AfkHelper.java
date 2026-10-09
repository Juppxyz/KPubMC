package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AfkHelper {

    private static final long AFK_SECONDS = 30 * 60;
    private static final long WARN_SECONDS = 30;
    private static final long KICK_AFTER_MS = AFK_SECONDS * 1000L;
    private static final long WARN_AT_MS = (AFK_SECONDS - WARN_SECONDS) * 1000L;

    // thread-safe, the chat marks activity from the async chat thread
    private static final Map<UUID, Long> lastActivityMs = new ConcurrentHashMap<>();

    public static void removePlayer(UUID id) {
        lastActivityMs.remove(id);
    }

    public static void markActivity(Player p) {
        lastActivityMs.put(p.getUniqueId(), System.currentTimeMillis());
    }

    public static @NotNull BukkitRunnable tickKickTask() {
        return new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();
                    long last = lastActivityMs.getOrDefault(id, now);
                    long idleMs = now - last;

                    if (idleMs >= WARN_AT_MS && idleMs < KICK_AFTER_MS) {
                        long remainingSec = (KICK_AFTER_MS - idleMs + 999) / 1000;

                        p.sendActionBar(
                                Component.text("Inaktiv: ", NamedTextColor.DARK_GRAY)
                                        .append(Component.text("Kick in ", NamedTextColor.RED))
                                        .append(Component.text(remainingSec + "s", NamedTextColor.YELLOW))
                                        .append(Component.text(" (bewegen um zu bleiben)", NamedTextColor.GRAY))
                        );
                        continue;
                    }

                    if (idleMs >= KICK_AFTER_MS) {
                        removePlayer(id);
                        p.kick(
                                Component.text("KlotzscherPub", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                                        .append(Component.newline())
                                        .append(Component.text("Du wurdest wegen Inaktivität gekickt.", NamedTextColor.RED))
                        );
                    }
                }

            }
        };
    }
}
