package xyz.jupp.minecraft.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import xyz.jupp.minecraft.utils.PermissionsUtil;

import java.util.ArrayList;

public class CommandBlockListener implements Listener {

    private static ArrayList<String> allowedCommands = null;
    public static ArrayList<String> getAllowedCommands() {
        if (allowedCommands == null) {
            allowedCommands = new ArrayList<>();
            allowedCommands.add("money");
            allowedCommands.add("geld");
            allowedCommands.add("schilling");
            allowedCommands.add("config");
            allowedCommands.add("hilfe");
            allowedCommands.add("help");
            allowedCommands.add("slimechunk");
            allowedCommands.add("sc");
            allowedCommands.add("einladungen");
            allowedCommands.add("invites");
            allowedCommands.add("team");
        }
        return allowedCommands;
    }

    @EventHandler
    public void onCommandExecute(PlayerCommandPreprocessEvent event){
        Player player = event.getPlayer();
        String msg = event.getMessage();
        if (!player.isOp()) {
            if (!getAllowedCommands().contains(msg) && !msg.startsWith("/team") && !msg.startsWith("/msg")) {
                event.setCancelled(true);
                PermissionsUtil.sendNoPermMsg(player);
            }
        }

    }

}
