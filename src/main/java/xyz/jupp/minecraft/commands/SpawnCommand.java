package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.PlayerTeleport;
import xyz.jupp.minecraft.utils.Tasks;

public class SpawnCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player) {
            // Entity#isOnGround, Player only re-declares it as deprecated
            if (!((Entity) player).isOnGround()) {
                player.sendMessage(Main.getChatPrefix() + "§cDu musst auf dem Boden sein um dich zum Spawn teleportieren zu können.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return false;
            }

            // next tick, so the countdown text still follows the usage line Bukkit sends for 'return false'
            Tasks.sync(() -> new PlayerTeleport().teleportAfter(player, Locations.getCurrentSpawn()));
        }

        return false;
    }
}
