package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

public class DonateCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            player.sendMessage(Main.getChatPrefix() + "Du möchtest den Server unterstützen?");
            player.sendMessage(Main.getChatPrefix() + "Schau gerne mal hier vorbei:");
            player.sendMessage(Main.getChatPrefix() + "§a" + "https://paypal.me/juppxyz");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f,1f);
        }
        return false;
    }

}
