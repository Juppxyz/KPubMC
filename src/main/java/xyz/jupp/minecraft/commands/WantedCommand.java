package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.Loans;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.ArrayList;
import java.util.List;

public class WantedCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] strings) {
        if (commandSender instanceof Player player) {

            Tasks.supplyAsync(WantedCommand::loadWantedLines, lines -> {
                if (lines.isEmpty()) {
                    player.sendMessage(Main.getChatPrefix() + " ");
                    player.sendMessage(Main.getChatPrefix() + "§aAktuell werden §ckeine §aSpieler gesucht.");
                    player.sendMessage(Main.getChatPrefix() + " ");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    return;
                }

                player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
                player.sendMessage("§8--=== §4§lWANTED §8===-- ");
                player.sendMessage("§7----------------------");
                lines.forEach(player::sendMessage);
            });

        }

        return false;
    }

    // blocking (database, names of offline players); empty if nobody is wanted
    private static List<String> loadWantedLines() {
        List<String> lines = new ArrayList<>();
        for (PlayerRepository.PlayerData wantedPlayer : PlayerRepository.getWantedPlayers()) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(wantedPlayer.uuid());

            long diffMillis = wantedPlayer.jailEnd() - System.currentTimeMillis();
            String left = wantedPlayer.jailEnd() == 0 ? "bis gefasst" : Math.round(diffMillis / (1000d * 60d * 60d)) + "h";

            lines.add("§fName§8: §c" + offlinePlayer.getName());
            lines.add("§fVerbleibende Zeit§8: §c§o" + left);
            lines.add(" ");
            lines.add("§fBelohnung§8» " + Main.getCurrencyName(Loans.bounty(wantedPlayer.uuid())) + " §7und §a1000 Team-Punkte");
            lines.add("§7----------------------");
        }
        return lines;
    }
}
