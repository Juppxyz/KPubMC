package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.TeamCollection;

public class ChatListener implements Listener {

    @EventHandler
    public void onSendMessage(AsyncPlayerChatEvent event) {
        String msg = event.getMessage().replace("&", "§").replace("%", "Prozent");
        Player player = event.getPlayer();
        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);

        if (playerCacheObject.getTeamID() == null) {
            event.setFormat(formatChatMessage(player.getPlayerListName(), msg));
        } else {
            TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
            if (teamCacheObject != null && msg.startsWith("@")) {
                handleTeamChat(event, player, msg, playerCacheObject, teamCacheObject);
                return;
            }

            String teamPrefix = formatTeamPrefix(teamCacheObject, player.getName());
            event.setFormat(formatChatMessage(teamPrefix, msg));
        }
    }

    private void handleTeamChat(AsyncPlayerChatEvent event, Player player, String msg, PlayerCacheObject playerCacheObject, TeamCacheObject teamCacheObject) {
        event.setCancelled(true);
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            TeamCollection teamCollection = new TeamCollection(playerCacheObject.getTeamID());
            if (teamCollection.getTeamPoints() >= 15) {
                sendTeamBroadcast(msg.substring(1), playerCacheObject);
            } else {
                player.sendMessage(Main.getChatPrefix() + "§cDein Team hat noch nicht genügend Punkte für den TeamChat.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
            }
        });
    }

    private String formatTeamPrefix(TeamCacheObject teamCacheObject, String playerName) {
        return String.format("§8[%s%s§8] %s%s", teamCacheObject.getTeamColor(), teamCacheObject.getTeamName(), teamCacheObject.getTeamColor(), playerName);
    }

    private String formatChatMessage(String prefix, String msg) {
        return String.format("%s§8» §f%s", prefix, msg);
    }

    private void sendTeamBroadcast(@NotNull String msg, PlayerCacheObject playerCacheObject) {
        String prefix = String.format("§8[%s%s-Chat§8] §f(%s)§8» §f", playerCacheObject.getTeamCacheObject().getTeamColor(), playerCacheObject.getTeamCacheObject().getTeamName(), playerCacheObject.getPlayer().getName());
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerCacheObject tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            if (tmpPlayerCacheObject.getTeamID() != null && tmpPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                player.sendMessage(prefix + msg);
            }
        }
    }
}
