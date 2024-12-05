package xyz.jupp.minecraft.listener;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.commands.SpecCommand;
import xyz.jupp.minecraft.database.PlayerCollection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class JoinQuitListener implements Listener {

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            PlayerCollection playerCollection = new PlayerCollection(event.getPlayer());
            playerCollection.createNewPlayerInDatabase();
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.setJoinMessage(null);
        Player player = event.getPlayer();

        if (!player.hasPlayedBefore()) {
            player.teleport(new Location(Bukkit.getWorld("world_MCWinter"), 92624.500D, 72.5000D, 114430.500D));
        }

        ArrayList<UUID> specMode = SpecCommand.getSpecMode();
        if (!specMode.isEmpty()) {
            for (UUID onlineUUID : specMode){
                Player target = Bukkit.getPlayer(onlineUUID);
                if (target != null && target.isOnline()) {
                    player.hidePlayer(target);
                }
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            //handleDailyLoginStreak(player);
            handleFirstJoinMessages(player);
            setTeamDisplayNames(player);
            Bukkit.broadcastMessage(String.format("§8[§a+§8] %s §fhat den Server betreten.", player.getPlayerListName()));
        });
    }




    private void handleFirstJoinMessages(Player player) {
        if (!player.hasPlayedBefore()) {
            Bukkit.broadcastMessage("§8§l[§a§l+§8§l] §a§l" + player.getName() + " §f§lhat den Server zum ersten mal betreten.");
            CacheHandler.getInstance().getPlayerInCache(player);
            player.setPlayerListName("§a" + player.getName());
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
            sendWelcomeMessages(player);
        }
    }

    private void sendWelcomeMessages(Player player) {
        player.sendMessage(Main.getChatPrefix() + "§a§lHerzlich Willkommen auf unserem Minecraft-Server!");
        player.sendMessage(Main.getChatPrefix() + "§fMelde dich bei Fragen oder Problemen einfach");
        player.sendMessage(Main.getChatPrefix() + "§fim Discord Channel §a§l#minecraft§f.");
        player.sendMessage(Main.getChatPrefix() + "§aViel Spaß!");
    }

    private void setTeamDisplayNames(Player player) {
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(player).getTeamCacheObject();
        if (team != null) {
            String displayName = team.getTeamColor() + player.getName();
            if (team.getTeamVices().contains(player.getUniqueId().toString())) {
                displayName = team.getTeamColor() + "§o" + player.getName();
            } else if (team.getTeamOwner().equals(player.getUniqueId().toString())) {
                displayName = team.getTeamColor() + "§l" + player.getName();
            }
            player.setPlayerListName(displayName);
            player.setDisplayName(displayName);
        } else {
            player.setPlayerListName("§a" + player.getName());
            player.setDisplayName("§a" + player.getName());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        CacheHandler.getInstance().removePlayerFromCache(player);
        if (SpecCommand.getSpecMode(player.getUniqueId())) {
            SpecCommand.changeSpecMode(player.getUniqueId());
            event.setQuitMessage(null);
            return;
        }
        event.setQuitMessage("§8[§c-§8] §a" + player.getPlayerListName() + " §fhat den Server verlassen.");
    }
}
