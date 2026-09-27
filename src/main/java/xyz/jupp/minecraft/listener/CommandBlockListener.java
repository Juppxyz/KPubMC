package xyz.jupp.minecraft.listener;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.CommandLogRepository;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.Set;

public class CommandBlockListener implements Listener {

    // exact matches, the prefixes with arguments are checked in isAllowedForNormalPlayer
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "/money", "/geld", "/schilling", "/config", "/hilfe", "/help", "/sc", "/slimechunk",
            "/einladungen", "/invites", "/team", "/ranking", "/warp", "/head", "/kopf", "/donate",
            "/spenden", "/spawn", "/regeln", "/rules", "/ec", "/enderchest", "/wanted", "/sit",
            "/ursprung", "/origin", "/removechunk", "/staatskasse", "/kasse"
    );

    private static boolean isAllowedForNormalPlayer(String msg) {
        return ALLOWED_COMMANDS.contains(msg)
                || msg.startsWith("/team")
                || msg.startsWith("/msg")
                || msg.startsWith("/head")
                || msg.startsWith("/kopf");
    }

    @EventHandler
    public void onCommandExecute(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage();

        // every command is logged, also the blocked ones
        Tasks.async(() -> CommandLogRepository.log(player, msg));

        if (PermissionsUtil.isPlayerAdmin(player)) return;

        if (JailHandler.isPlayerInJail(player)) {
            event.setCancelled(true);
            player.sendMessage(Main.getChatPrefix() + "§fIm Gefängnis kannst du keine Befehle ausführen.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }

        if (!isAllowedForNormalPlayer(msg)) {
            event.setCancelled(true);
            PermissionsUtil.sendNoPermMsg(player);
        }
    }

}
