package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PlayerTeleport;

public class NullpointCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player) {
            // Entity#isOnGround, Player only re-declares it as deprecated
            if (!((Entity) player).isOnGround()) {
                player.sendMessage(Main.getChatPrefix() + "§cDu musst auf dem Boden sein um dich zum Nullpunkt teleportieren zu können.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return true;
            }

            new PlayerTeleport().teleportAfter(player, new Location(Bukkit.getWorld("world_MCWinter"),87.500, 72,-119.500));
        }

        return true;
    }

}
