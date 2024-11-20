package xyz.jupp.minecraft.utils;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

public class PermissionsUtil {

    public static boolean isPlayerAdmin(@NotNull Player player){
        return (player.isOp()
                || player.getUniqueId().toString().equals("520be392-80da-4e7c-8cb4-4f264701417f")
                || player.getUniqueId().toString().equals("1a9a6f68-f6e7-4f5b-9c79-4c15fbae08d8"));
    }


    public static boolean isPlayerMod(@NotNull Player player){
        return (isPlayerAdmin(player) || player.getUniqueId().toString().equals("c176b44e-121a-426b-a742-56a70fc28dc3"));
    }

    public static void sendNoPermMsg(@NotNull Player player){
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
        player.sendMessage(Main.getChatPrefix() + "§cDas darfst du leider nicht.");

    }

}
