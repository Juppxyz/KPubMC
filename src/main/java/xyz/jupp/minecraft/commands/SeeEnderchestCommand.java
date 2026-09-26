package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class SeeEnderchestCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] args) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;

            if (!PermissionsUtil.isPlayerAdmin(player)) {
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            }

            if (args.length == 0) {
                player.sendMessage(Main.getChatPrefix() + "Bitte nutze§8: §a/seeec <Name>");
                return false;
            }

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(args[0]);
            if (offlinePlayer == null || !offlinePlayer.hasPlayedBefore() || !offlinePlayer.isOnline()) {
                player.sendMessage(Main.getChatPrefix() + "§cDieser Spieler ist unbekannt//offline.");
                return false;
            }


        }
        return false;
    }
}
