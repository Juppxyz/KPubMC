package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.CommandLogCollection;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.PermissionsUtil;

import java.util.ArrayList;
import java.util.List;

public class CommandBlockListener implements Listener {

    private static List<String> allowedCommands = null;

    public static List<String> getAllowedCommands() {
        if (allowedCommands == null) {
            allowedCommands = new ArrayList<>();
            allowedCommands.add("/money");
            allowedCommands.add("/geld");
            allowedCommands.add("/schilling");
            allowedCommands.add("/config");
            allowedCommands.add("/hilfe");
            allowedCommands.add("/help");
            allowedCommands.add("/sc");
            allowedCommands.add("/slimechunk");
            allowedCommands.add("/einladungen");
            allowedCommands.add("/invites");
            allowedCommands.add("/team");
            allowedCommands.add("/ranking");
            allowedCommands.add("/warp");
            allowedCommands.add("/head");
            allowedCommands.add("/kopf");
            allowedCommands.add("/donate");
            allowedCommands.add("/spenden");
            allowedCommands.add("/spawn");
            allowedCommands.add("/regeln");
            allowedCommands.add("/rules");
            allowedCommands.add("/ec");
            allowedCommands.add("/enderchest");
            allowedCommands.add("/wanted");
        }
        return allowedCommands;
    }

    private boolean isAllowedForNormalPlayer(String msg) {
        // exakte Matches aus der Liste
        if (getAllowedCommands().contains(msg)) return true;

        // Prefix-Whitelists (mit Argumenten etc.)
        if (msg.startsWith("/team")) return true;
        if (msg.startsWith("/msg")) return true;
        if (msg.startsWith("/head")) return true;
        if (msg.startsWith("/kopf")) return true;

        return false;
    }

    @EventHandler
    public void onCommandExecute(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage();
        boolean isAdmin = PermissionsUtil.isPlayerAdmin(player);
        
        if (!isAdmin) {
            if (JailHandler.isPlayerInJail(player)) {
                event.setCancelled(true);
                player.sendMessage(Main.getChatPrefix() + "§fIm Gefängnis kannst du keine Befehle ausführen.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                Bukkit.getScheduler().runTaskAsynchronously(
                        Main.getInstance(),
                        () -> new CommandLogCollection(player, msg).addNewEntry()
                );
                return;
            }

            if (!isAllowedForNormalPlayer(msg)) {
                event.setCancelled(true);
                PermissionsUtil.sendNoPermMsg(player);
                Bukkit.getScheduler().runTaskAsynchronously(
                        Main.getInstance(),
                        () -> new CommandLogCollection(player, msg).addNewEntry()
                );
                return;
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(
                Main.getInstance(),
                () -> new CommandLogCollection(player, msg).addNewEntry()
        );
    }

}
