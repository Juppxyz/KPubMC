package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.AdminView;
import xyz.jupp.minecraft.utils.PermissionsUtil;

/** /wirtschaft (admins, in game): the economy menu with its switches. */
public class EconomyCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Main.getChatPrefix() + "§fDas Wirtschafts-Menü gibt es nur im Spiel.");
            return true;
        }
        if (!PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }
        AdminView.open(player);
        return true;
    }

}
