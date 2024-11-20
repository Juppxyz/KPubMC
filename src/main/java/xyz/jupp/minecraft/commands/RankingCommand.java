package xyz.jupp.minecraft.commands;

import com.mongodb.client.FindIterable;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.TeamCollection;
import java.util.Iterator;

public class RankingCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player) {
            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                Player player = (Player) commandSender;
                FindIterable<Document> iterable = TeamCollection.getAllTeamDocumentsSorted();
                Iterator<Document> iterator = iterable.iterator();
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,2f,2f);
                player.sendMessage(" ");
                player.sendMessage("§8=-- §a§lRanking §8--=");
                int position = 1;
                TeamCacheObject teamCacheObject = null;
                while (iterator.hasNext()) {
                    Document document = iterator.next();
                    teamCacheObject = CacheHandler.getInstance().getTeamCacheObject(document.getString("teamID"));
                    player.sendMessage(String.format("§a%d. §8- %s%s §8(§a%d§8)", position, teamCacheObject.getTeamColor(), teamCacheObject.getTeamName(), document.getInteger("teamPoints")));
                    position++;
                }
                player.sendMessage(" ");
            });
        }
        return false;
    }

}
