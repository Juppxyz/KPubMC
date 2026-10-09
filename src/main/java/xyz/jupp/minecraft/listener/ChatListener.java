package xyz.jupp.minecraft.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import xyz.jupp.minecraft.team.Teams;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

// Runs on the async chat thread: the caches are thread-safe, sounds are played on the main thread.
public class ChatListener implements Listener {

    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        final String teamID = pco.getTeamID();
        final TeamCacheObject team = teamID == null ? null : pco.getTeamCacheObject();

        // Rohtext der Nachricht (ohne Farben) ermitteln:
        final String plain = PLAIN.serialize(event.message());

        if (plain.startsWith("@")) {
            if (team == null) {
                deny(event, player, "§cDu bist in keinem Team.");
                return;
            }

            if (team.getLevel() < Teams.CHAT_LEVEL) {
                deny(event, player, "§fFür den TeamChat muss dein Team mindestens Level §a" + Teams.CHAT_LEVEL + " §fsein.");
                return;
            }

            event.viewers().removeIf(aud -> !(aud instanceof Player tgt)
                    || !teamID.equals(CacheHandler.getInstance().getPlayerInCache(tgt).getTeamID()));

            event.message(LEGACY_AMP.deserialize(plain.substring(1)));

            Component prefix = Text.section(String.format(
                    "§8[%s%s-Chat§8] §f(%s)§8» §f",
                    team.getTeamColor(), team.getTeamName(), player.getName()
            ));
            event.renderer(ChatRenderer.viewerUnaware((src, srcName, msg) -> prefix.append(msg)));
            return;
        }

        // --- Globaler Chat mit Team-Prefix ---
        final String prefixLegacy;
        if (team == null) {
            prefixLegacy = Text.legacy(player.playerListName()) + "§8» §f";
        } else {
            prefixLegacy = String.format("§8[%s%s§8] %s%s§8» §f",
                    team.getTeamColor(), team.getTeamName(), team.getTeamColor(), player.getName());
        }
        final Component prefix = Text.section(prefixLegacy);
        event.message(LEGACY_AMP.deserialize(plain));

        event.renderer(ChatRenderer.viewerUnaware((src, srcName, msg) -> prefix.append(msg)));
    }

    private static void deny(AsyncChatEvent event, Player player, String message) {
        event.setCancelled(true);
        Tasks.sync(() -> {
            player.sendMessage(Main.getChatPrefix() + message);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
        });
    }
}
