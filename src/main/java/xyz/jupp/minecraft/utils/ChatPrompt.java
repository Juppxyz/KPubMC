package xyz.jupp.minecraft.utils;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One answer typed into the chat instead of a message: a menu asks (e.g. the Sheriff for a name), the player's next chat
 * line goes to the waiting question and to nobody else. CANCEL cancels; after TIMEOUT_MILLIS the line is normal chat again.
 */
public final class ChatPrompt implements Listener {

    public static final String CANCEL = "abbrechen";
    private static final long TIMEOUT_MILLIS = 60_000L;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private record Waiting(String prefix, Consumer<String> onAnswer, long expiresAt) {}

    private static final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

    /** Main thread: asks; the next chat line of the player (without colour codes, trimmed) goes to onAnswer on the main thread. */
    public static void ask(@NotNull Player player, @NotNull String prefix, @NotNull String question, @NotNull Consumer<String> onAnswer) {
        waiting.put(player.getUniqueId(), new Waiting(prefix, onAnswer, System.currentTimeMillis() + TIMEOUT_MILLIS));
        player.sendMessage(prefix + question);
        player.sendMessage(prefix + "§7Schreib es in den Chat, oder §f" + CANCEL + "§7. (60 Sekunden)");
    }

    // LOWEST: before the chat formatting, the answer is no chat message
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Waiting question = waiting.remove(player.getUniqueId());
        // an old question is forgotten, the line is normal chat
        if (question == null || System.currentTimeMillis() > question.expiresAt()) return;
        event.setCancelled(true);
        String answer = PLAIN.serialize(event.message()).replace('§', ' ').trim();
        Tasks.sync(() -> {
            if (!player.isOnline()) return;
            if (answer.equalsIgnoreCase(CANCEL)) {
                player.sendMessage(question.prefix() + "§7Abgebrochen.");
                return;
            }
            question.onAnswer().accept(answer);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }
}
