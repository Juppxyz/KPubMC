package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PlayerTeleport;

public class SpawnCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            if (!player.isOnGround()) {
                player.sendMessage(Main.getChatPrefix() + "§cDu musst auf dem Boden sein um dich zum Spawn teleportieren zu können.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return false;
            }

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                PlayerTeleport playerTeleport = new PlayerTeleport();
                Location targetLocation = new Location(Bukkit.getWorld("world_MCWinter"), 92624.500D, 72.5000D, 114430.500D);
                playerTeleport.teleportAfter(player, targetLocation);
            });
        }

        return false;
    }
}
