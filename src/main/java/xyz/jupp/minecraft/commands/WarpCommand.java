package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.inventory.WarpInventory;

public class WarpCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            if (!player.isOnGround()) {
                player.sendMessage(Main.getChatPrefix() + "Du musst auf dem Boden sein um das Warp-Menü zu öffnen.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return false;
            }
            WarpInventory.openInventory(player, 1);
        }
        return false;
    }
}
