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

public class EnderchestCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] strings) {

        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;

            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            if (playerCacheObject.getTeamID() == null || playerCacheObject.getTeamCacheObject().getLevel() < 3) {
                player.sendMessage(Main.getChatPrefix() + "§fDies können nur §aTeams §fmit der Stufe §a3 §foder höher.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                return false;
            }

            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.5f, 0.4f);
            player.openInventory(player.getEnderChest());
        }

        return false;
    }

}
