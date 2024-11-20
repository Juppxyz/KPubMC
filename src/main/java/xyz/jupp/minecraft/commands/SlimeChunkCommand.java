package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

public class SlimeChunkCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player) {
            Player player = (Player)commandSender;
            player.sendMessage(Main.getChatPrefix() + "Du bist derzeit in " + (player.getLocation().getChunk().isSlimeChunk() ? "§aeinem" : "§ckeinem") + " §fSlimeChunk.");
        }
        return false;
    }
}
