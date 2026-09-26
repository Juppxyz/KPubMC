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
import xyz.jupp.minecraft.utils.TabListUtil;

public class ConfigCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player) {
            if (!PermissionsUtil.isPlayerAdmin(player)) {
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            }

            reload();
            TabListUtil.updateTabForAll();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
            player.sendMessage(Main.getChatPrefix() + "Die Config wurde §aerfolgreich §faktualisiert!");
        }else {
            reload();
            Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "Die Config wurde §aerfolgreich §faktualisiert!");
        }
        return false;
    }

    private static void reload() {
        ConfigManager.getManager().updateConfig();
        BlackMarketHandler.forceReroll();
    }

}
