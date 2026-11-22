package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.utils.HeadBoard;
import xyz.jupp.minecraft.utils.PermissionsUtil;

import java.util.List;

/**
 * /todestafel [<Platz> [entfernen]]: the death board, three heads with a sign under each for the players who died most
 * this season (deaths outside the arena, PlayerRepository.addDeath). Set up like the Gesucht board.
 */
public class DeathBoardCommand implements CommandExecutor, TabCompleter {

    public static final HeadBoard BOARD = new HeadBoard("death_board", "Todes-Tafel",
            "die " + HeadBoard.SPOTS + " Spieler mit den meisten Toden", "die Tode", DeathBoardCommand::mostDeaths);

    // worker: most deaths first; on a tie whoever got there first
    private static List<HeadBoard.Entry> mostDeaths() {
        return PlayerRepository.mostDeaths(HeadBoard.SPOTS).stream().map(row -> {
            String name = Bukkit.getOfflinePlayer(row.uuid()).getName();
            return new HeadBoard.Entry(row.uuid(), name == null ? "Unbekannt" : name, row.deaths() + (row.deaths() == 1 ? " Tod" : " Tode"));
        }).toList();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }
        BOARD.command(sender, "/" + label, args);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) return List.of();
        return HeadBoard.complete(args);
    }

}
