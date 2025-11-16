package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.utils.BlackMarketHandler;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class ConfigCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            if (!PermissionsUtil.isPlayerAdmin(player)) {
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            }

            ConfigManager.getManager().updateConfig();
            BlackMarketHandler.forceReroll();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
            player.sendMessage(Main.getChatPrefix() + "Die Config wurde §aerfolgreich §faktualisiert!");
        }else {
            ConfigManager.getManager().updateConfig();
            BlackMarketHandler.forceReroll();
            Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "Die Config wurde §aerfolgreich §faktualisiert!");
        }
        return false;
    }

}
