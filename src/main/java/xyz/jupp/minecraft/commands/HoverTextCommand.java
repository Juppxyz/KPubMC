package xyz.jupp.minecraft.commands;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

import java.util.Arrays;
import java.util.stream.Collectors;

public class HoverTextCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            if (!PermissionsUtil.isPlayerAdmin(player)){
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            }
            if (args.length == 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                player.sendMessage(Main.getChatPrefix() + "Bitte verwende: §a/hover <Text>");
                return false;
            }
            String stringBuilder = Arrays.stream(args).map(arg -> " " + arg.replaceAll("&", "§")).collect(Collectors.joining());
            createNewArmorStand(player.getLocation(), stringBuilder);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
            player.sendMessage(Main.getChatPrefix() + "Der HoverText wurde §aerfolgreich §ferstellt.");
        }
        return false;
    }


    private ArmorStand createNewArmorStand(Location location, String title) {
        ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(location.add(0.5, 1, 0.5), EntityType.ARMOR_STAND);
        armorStand.setGravity(false);
        armorStand.setVisible(false);
        armorStand.setInvulnerable(true);
        armorStand.setCustomNameVisible(true);
        armorStand.setCustomName(title);
        return armorStand;
    }

}
