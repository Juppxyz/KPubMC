package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.TeamRepository;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.ArrayList;
import java.util.List;

public class RankingCommand implements CommandExecutor {

    private record Ranking(List<String> lines, boolean complete) {}

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player) {
            Tasks.supplyAsync(RankingCommand::loadRanking, ranking -> {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,2f,2f);
                player.sendMessage(" ");
                player.sendMessage("§8=-- §a§lRanking §8--=");
                ranking.lines().forEach(player::sendMessage);
                if (ranking.complete()) {
                    player.sendMessage(" ");
                }
            });
        }
        return false;
    }

    // blocking; name, colour and level come from the team cache (a team is loaded once), the points from the query.
    // The list ends at a team that cannot be loaded, as it did before.
    private static Ranking loadRanking() {
        List<TeamRepository.RankedTeam> teams = TeamRepository.getRanking();

        List<String> lines = new ArrayList<>(teams.size());
        int position = 1;
        for (TeamRepository.RankedTeam team : teams) {
            TeamCacheObject teamCacheObject = CacheHandler.getInstance().getTeamCacheObject(team.teamID());
            if (teamCacheObject == null) {
                return new Ranking(lines, false);
            }
            lines.add(String.format("§a%d. §8- %s%s §8(§a%d§8) §8| §aLevel %d", position, teamCacheObject.getTeamColor(), teamCacheObject.getTeamName(), team.points(), teamCacheObject.getLevel()));
            position++;
        }
        return new Ranking(lines, true);
    }

}
