package xyz.jupp.minecraft.database;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Table 'command_log'. Blocking, call it off the main thread.
 */
public final class CommandLogRepository {

    private CommandLogRepository() {}

    public static void log(@NotNull Player player, @NotNull String command) {
        Database.update("INSERT INTO command_log (player_uuid, player_name, command) VALUES (?, ?, ?)",
                player.getUniqueId(), player.getName(), command);
    }

}
