package xyz.jupp.minecraft.commands;

import com.mongodb.client.FindIterable;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;

import java.util.List;
import java.util.UUID;

public class WantedCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] strings) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
                List<Document> wantedPlayers = pco.getPlayerCollection().getWantedPlayers();

                if (wantedPlayers.isEmpty()) {
                    player.sendMessage(Main.getChatPrefix() + " ");
                    player.sendMessage(Main.getChatPrefix() + "§aAktuell werden §ckeine §aSpieler gesucht.");
                    player.sendMessage(Main.getChatPrefix() + " ");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    return;
                }

                player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
                player.sendMessage("§8--=== §4§lWANTED §8===-- ");
                player.sendMessage("§7----------------------");
                for(Document wantedPlayer : wantedPlayers) {
                    String uuid = wantedPlayer.getString("uuid");
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(uuid));

                    long diffMillis = wantedPlayer.getLong("jailEnd") - System.currentTimeMillis();
                    double diffHours = diffMillis / (1000d * 60d * 60d);

                    player.sendMessage("§fName§8: §c" + offlinePlayer.getName());
                    player.sendMessage("§fVerbleibende Zeit§8: §c§o" + diffHours + "h");
                    player.sendMessage(" ");
                    player.sendMessage("§fBelohnung§8» " + Main.getCurrencyName(10000) + " §7und §a1000 Team-Punkte");
                    player.sendMessage("§7----------------------");
                }

            });

        }

        return false;
    }
}
