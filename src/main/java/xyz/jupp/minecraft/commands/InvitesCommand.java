package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.utils.Tasks;

public class InvitesCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player){
            // the toggle writes to the database (blocking), the answer is sent on the main thread
            Tasks.async(() -> {
                PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                boolean inTeam = playerCacheObject.getTeamID() != null;
                boolean newState = playerCacheObject.changeTeamInvite();
                Tasks.sync(() -> {
                    if (inTeam) {
                        player.sendMessage(Main.getChatPrefix() + "Kleiner Hinweis: Solange du in einem Team bist, kannst du von niemanden eingeladen werden.");
                    }
                    if (newState) {
                        player.sendMessage(Main.getChatPrefix() + "§aDu kannst nun von Teams eingeladen werden.");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                    }else {
                        player.sendMessage(Main.getChatPrefix() + "§cDich können nun keine Teams mehr einladen.");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                    }
                });
            });
        }
        return false;
    }
}
