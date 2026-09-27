package xyz.jupp.minecraft.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.economy.HondoView;
import xyz.jupp.minecraft.commands.SpecCommand;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.LastSeen;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.TabListUtil;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.UUID;

public class JoinQuitListener implements Listener {

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        PlayerRepository.createIfAbsent(uuid);
        PlayerRepository.touch(uuid);
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            CacheHandler.getInstance().preloadPlayer(uuid);
        }
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.joinMessage(Component.empty());

        int activeState = LastSeen.getJoinState(player);
        applyTeamDisplayNames(player);
        TabListUtil.updateTabFor(player);

        if (activeState == 1) {
            player.teleport(Locations.getCurrentSpawn());
            Bukkit.broadcast(Text.section(
                    "§8§l[§a§l+§8§l] §a§l" + player.getName() + " §f§lhat den Server zum ersten Mal betreten."
            ));
            sendWelcome(player);

        } else if (activeState == 2) {
            player.teleport(Locations.getCurrentSpawn());
            Bukkit.broadcast(Text.section(
                    "§8§l[§a§l+§8§l] §a§l" + player.getName() + " §f§list nach langer Zeit wieder zurückgekehrt"
            ));

        } else {
            Bukkit.broadcast(Text.section(
                    String.format("§8[§a+§8] %s §fhat den Server betreten.", Text.legacy(player.playerListName()))
            ));
        }

        for (UUID uuid : SpecCommand.getSpecMode()) {
            Player target = Bukkit.getPlayer(uuid);
            if (target != null && target.isOnline()) {
                player.hidePlayer(Main.getInstance(), target);
            }
        }

        // jail handling, JailHandler.handleJoin can write to the database
        Tasks.async(() -> JailHandler.handleJoin(player));
        // goods from a Hondo trade the player left during
        HondoView.deliverPending(player);

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

        event.quitMessage(Text.section(
                "§8[§c-§8] §a" + Text.legacy(player.playerListName()) + " §fhat den Server verlassen."));
    }


    private void applyTeamDisplayNames(Player player) {
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(player).getTeamCacheObject();
        Component name = Text.section(teamPlayerName(player, team, false));
        player.playerListName(name);
        player.displayName(name);
    }

    /**
     * Team colour plus §l for the owner and §o for vices, "§a" without a team.
     * The join checks the vices first, the respawn the owner: ownerFirst keeps that order for a player listed as both.
     */
    static String teamPlayerName(@NotNull Player player, @Nullable TeamCacheObject team, boolean ownerFirst) {
        if (team == null) return "§a" + player.getName();
        String uuid = player.getUniqueId().toString();
        boolean owner = uuid.equals(team.getTeamOwner());
        boolean vice = team.getTeamVices().contains(uuid);
        String role = owner && (ownerFirst || !vice) ? "§l" : vice ? "§o" : "";
        return team.getTeamColor() + role + player.getName();
    }


    private void sendWelcome(Player p) {
        p.sendMessage(Text.section(Main.getChatPrefix() + "§a§lHerzlich Willkommen auf unserem Minecraft-Server!"));
        p.sendMessage(Text.section(Main.getChatPrefix() + "§fMelde dich bei Fragen oder Problemen einfach"));
        p.sendMessage(Text.section(Main.getChatPrefix() + "§fim Discord Channel §a§l#minecraft§f."));
        p.sendMessage(Text.section(Main.getChatPrefix() + "§aViel Spaß!"));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
    }
}
