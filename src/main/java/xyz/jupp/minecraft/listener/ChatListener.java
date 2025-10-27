package xyz.jupp.minecraft.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.TeamCollection;

import java.util.Objects;

public class ChatListener implements Listener {

    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SEC = LegacyComponentSerializer.legacySection();

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);

        // Rohtext der Nachricht (ohne Farben) ermitteln:
        final String plain = event.message() == null
                ? ""
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());

        if (plain.startsWith("@")) {
            if (pco.getTeamID() == null || pco.getTeamCacheObject() == null) {
                event.setCancelled(true);
                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                    player.sendMessage(Main.getChatPrefix() + "§cDu bist in keinem Team.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                });
                return;
            }

            TeamCollection teamCollection = new TeamCollection(pco.getTeamID());
            if (teamCollection.getTeamPoints() < 15) {
                event.setCancelled(true);
                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                    player.sendMessage(Main.getChatPrefix() + "§cDein Team hat noch nicht genügend Punkte für den TeamChat.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                });
                return;
            }

            event.viewers().removeIf(aud -> {
                if (!(aud instanceof Player tgt)) return true;
                PlayerCacheObject tco = CacheHandler.getInstance().getPlayerInCache(tgt);
                return tco.getTeamID() == null || !Objects.equals(tco.getTeamID(), pco.getTeamID());
            });

            String teamMsg = plain.substring(1);
            event.message(LEGACY_AMP.deserialize(teamMsg));

            TeamCacheObject team = pco.getTeamCacheObject();
            String prefixLegacy = String.format(
                    "§8[%s%s-Chat§8] §f(%s)§8» §f",
                    team.getTeamColor(), team.getTeamName(), player.getName()
            );
            Component prefix = LEGACY_SEC.deserialize(prefixLegacy);

            event.renderer(ChatRenderer.viewerUnaware((src, srcName, msg) -> prefix.append(msg)));
            return;
        }

        // --- Globaler Chat mit Team-Prefix ---
        final String prefixLegacy;
        if (pco.getTeamID() == null || pco.getTeamCacheObject() == null) {
            prefixLegacy = player.getPlayerListName() + "§8» §f";
        } else {
            TeamCacheObject team = pco.getTeamCacheObject();
            prefixLegacy = String.format("§8[%s%s§8] %s%s§8» §f",
                    team.getTeamColor(), team.getTeamName(), team.getTeamColor(), player.getName());
        }
        final Component prefix = LEGACY_SEC.deserialize(prefixLegacy);
        event.message(LEGACY_AMP.deserialize(plain));

        event.renderer(ChatRenderer.viewerUnaware((src, srcName, msg) -> prefix.append(msg)));
    }
}
