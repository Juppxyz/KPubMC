package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

public class RulesCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            player.sendMessage(Main.getChatPrefix() + "Hier findest du die Regeln:");
            player.sendMessage(Main.getChatPrefix() + "§ahttps://discord.com/channels/628250514756468760/1312821207078273105/1312840351400333333");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
        }
        return false;
    }
}
