package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;

public class Logger {

    // Possible chat categories which serve for the different colors.
    public static enum ChatCategory {INFO, ADMIN, ERROR}

    // Simple wrapper method to insert the prefix into the console output.
    public static void console(@NotNull String message) {
        Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + message);
    }

    // A wrapper method which contains the prefix, the color and the broadcast message.
    public static void chat(@NotNull String message, @Nullable ChatCategory chatCategory) {
        String color = switch (chatCategory) {
            case ADMIN -> "§c";
            case ERROR -> "§4";
            case INFO -> "§a";
            default -> "§f";
        };
        Bukkit.getServer().broadcastMessage(String.format( "%s %s%s", Main.getChatPrefix(), color, message ));
    }

}
