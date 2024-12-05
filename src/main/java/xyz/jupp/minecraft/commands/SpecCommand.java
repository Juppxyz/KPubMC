package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

import java.util.ArrayList;
import java.util.UUID;

public class SpecCommand implements CommandExecutor {

    private static final ArrayList<UUID> specMode = new ArrayList<>();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (player.isOp()) {
                if (!specMode.contains(player.getUniqueId())){
                    for (Player online: Bukkit.getOnlinePlayers()){
                        online.hidePlayer(player);
                    }
                    player.sendMessage(Main.getChatPrefix() + "§fDein SpectatorMode ist nun: §aAN");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                    specMode.add(player.getUniqueId());
                    Bukkit.broadcastMessage("§8[§c-§8] §a" + player.getPlayerListName() + " §fhat den Server verlassen.");
                }else {
                    for (Player online: Bukkit.getOnlinePlayers()){
                        online.showPlayer(player);
                    }
                    player.sendMessage(Main.getChatPrefix() + "§fDein SpectatorMode ist nun: §aAUS");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                    specMode.remove(player.getUniqueId());
                    Bukkit.broadcastMessage(String.format("§8[§a+§8] %s §fhat den Server betreten.", player.getPlayerListName()));
                }
            }
        }
        return false;
    }

    public static boolean getSpecMode(@NotNull UUID playerUUID) {
        return specMode.contains(playerUUID);
    }

    public static void changeSpecMode(@NotNull UUID playerUUID) {
        specMode.remove(playerUUID);
    }

    public static ArrayList<UUID> getSpecMode() {
        return specMode;
    }

}
