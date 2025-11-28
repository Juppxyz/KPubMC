package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

import static xyz.jupp.minecraft.utils.MobEvent.createMobEvent;

public class MonsterEventCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] args) {
        if ((commandSender instanceof Player) && (args.length == 1)) {
            if (!commandSender.isOp()) return false;
            Player targetPlayer = Bukkit.getPlayer(args[0]);
            if (targetPlayer == null) return false;
            createMobEvent(targetPlayer);
            commandSender.sendMessage(Main.getChatPrefix() + "executed");
        }
        return false;
    }
}
