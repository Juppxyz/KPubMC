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
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.utils.PlayerTeleport;

public class NullpointCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            if (!player.isOnGround()) {
                player.sendMessage(Main.getChatPrefix() + "§cDu musst auf dem Boden sein um dich zum Nullpunkt teleportieren zu können.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return true;
            }

            //if (player.getLevel() < 30) {
            //    player.sendMessage(Main.getChatPrefix() + "§fUm zum Nullpunkt zu kommen musst du mindestens §aLevel 30 §fsein.");
            //    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            //    return true;
            //}

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                //PlayerCollection playerCollection = new PlayerCollection(player.getUniqueId().toString());
                //int money = playerCollection.getMoney();
                //if (money < 200) {
                //    player.sendMessage(Main.getChatPrefix() + "§fDas teleportieren zum Ursprung kostet " + Main.getCurrencyName(200) + "§f.");
                //    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                //    return;
                //}

                //playerCollection.updateMoney(money - 200);
                //player.sendMessage(Main.getChatPrefix() + "§c-200 Schilling");
                PlayerTeleport playerTeleport = new PlayerTeleport();
                playerTeleport.teleportAfter(player, new Location(Bukkit.getWorld("world_MCWinter"),87.500, 72,-119.500));
            });
        }

        return true;
    }

}
