package xyz.jupp.minecraft.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.commands.SpecCommand;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Locations;

import java.util.Objects;
import java.util.UUID;

public class JoinQuitListener implements Listener {

    private static final LegacyComponentSerializer LEGACY_SEC = LegacyComponentSerializer.legacySection();

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        PlayerCollection playerCollection = new PlayerCollection(uuid.toString());
        if (!playerCollection.existPlayerInDatabase()) {
            playerCollection.createNewPlayerInDatabase();
        }
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.joinMessage(Component.empty());

        if (!player.hasPlayedBefore()) {
            player.teleport(new Location(Objects.requireNonNull(Bukkit.getWorld("world_MCWinter")),
                    92624.5, 72.5, 114430.5));
        }

        if (!SpecCommand.getSpecMode().isEmpty()) {
            for (UUID uuid : SpecCommand.getSpecMode()) {
                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    player.hidePlayer(Main.getInstance(), target);
                }
            }
        }

        applyTeamDisplayNames(player);
        Component joinMsg = LEGACY_SEC.deserialize(
                String.format("§8[§a+§8] %s §fhat den Server betreten.", player.getPlayerListName())
        );
        Bukkit.broadcast(joinMsg);
        if (!player.hasPlayedBefore()) {
            Bukkit.broadcast(LEGACY_SEC.deserialize(
                    "§8§l[§a§l+§8§l] §a§l" + player.getName() + " §f§lhat den Server zum ersten Mal betreten."));
            sendWelcome(player);
        }

        // jail handling
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            JailHandler.handleJoin(event.getPlayer());
        });

    }


    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        CacheHandler.getInstance().removePlayerFromCache(player);

        if (SpecCommand.getSpecMode(player.getUniqueId())) {
            SpecCommand.changeSpecMode(player.getUniqueId());
            event.quitMessage(Component.empty());
            return;
        }

        Component msg = LEGACY_SEC.deserialize(
                "§8[§c-§8] §a" + player.getPlayerListName() + " §fhat den Server verlassen.");
        event.quitMessage(msg);
    }


    private void applyTeamDisplayNames(Player player) {
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(player).getTeamCacheObject();
        String display;
        if (team != null) {
            display = team.getTeamColor() + player.getName();
            if (team.getTeamVices().contains(player.getUniqueId().toString())) {
                display = team.getTeamColor() + "§o" + player.getName();
            } else if (team.getTeamOwner().equals(player.getUniqueId().toString())) {
                display = team.getTeamColor() + "§l" + player.getName();
            }
        } else {
            display = "§a" + player.getName();
        }
        // Adventure-Setzer statt deprecated String-Setter:
        Component comp = LEGACY_SEC.deserialize(display);
        player.playerListName(comp);
        player.displayName(comp);
    }


    private void sendWelcome(Player p) {
        p.sendMessage(LEGACY_SEC.deserialize(Main.getChatPrefix() + "§a§lHerzlich Willkommen auf unserem Minecraft-Server!"));
        p.sendMessage(LEGACY_SEC.deserialize(Main.getChatPrefix() + "§fMelde dich bei Fragen oder Problemen einfach"));
        p.sendMessage(LEGACY_SEC.deserialize(Main.getChatPrefix() + "§fim Discord Channel §a§l#minecraft§f."));
        p.sendMessage(LEGACY_SEC.deserialize(Main.getChatPrefix() + "§aViel Spaß!"));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
    }
}
