package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.CommandLogCollection;
import xyz.jupp.minecraft.utils.PermissionsUtil;

import java.util.ArrayList;

public class CommandBlockListener implements Listener {

    private static ArrayList<String> allowedCommands = null;
    public static ArrayList<String> getAllowedCommands() {
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
        }
        return allowedCommands;
    }

    @EventHandler
    public void onCommandExecute(PlayerCommandPreprocessEvent event){
        Player player = event.getPlayer();
        String msg = event.getMessage();
        if (!player.isOp()) {
            if (!getAllowedCommands().contains(msg) && !msg.startsWith("/team") && !msg.startsWith("/msg") && !msg.startsWith("/head") && !msg.startsWith("/kopf")) {
                event.setCancelled(true);
                PermissionsUtil.sendNoPermMsg(player);
            }
        }

        // Danny, sei leise. Sicher ist sicher.
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> new CommandLogCollection(player, msg).addNewEntry());
    }

}
