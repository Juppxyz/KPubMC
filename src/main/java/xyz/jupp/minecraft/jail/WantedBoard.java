package xyz.jupp.minecraft.jail;

import org.bukkit.Bukkit;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.economy.Loans;
import xyz.jupp.minecraft.utils.HeadBoard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The "Gesucht" board, set by admins with /bestrafung tafel: the most wanted players (highest bounty first) hang there
 * with their head and their name (see HeadBoard).
 */
public final class WantedBoard {

    private WantedBoard() {}

    public static final HeadBoard BOARD = new HeadBoard("wanted_board", "Gesucht-Tafel",
            "die " + HeadBoard.SPOTS + " höchsten Kopfgelder", null, WantedBoard::mostWanted);

    private record Wanted(UUID uuid, String name, int bounty, long wantedEnd) {}

    // worker: highest bounty first; on a tie wanted until caught, then the longest remaining time
    private static List<HeadBoard.Entry> mostWanted() {
        List<Wanted> wanted = new ArrayList<>();
        for (PlayerRepository.PlayerData data : PlayerRepository.getWantedPlayers()) {
            String name = Bukkit.getOfflinePlayer(data.uuid()).getName();
            int bounty = (int) Math.min(Integer.MAX_VALUE, Loans.bounty(data.uuid(), data.jailEnd()) + Bounties.pledged(data.uuid()));
            wanted.add(new Wanted(data.uuid(), name == null ? "Unbekannt" : name, bounty, data.jailEnd()));
        }
        wanted.sort(Comparator.comparingInt(Wanted::bounty).reversed()
                .thenComparing(entry -> entry.wantedEnd() != 0)
                .thenComparing(Comparator.comparingLong(Wanted::wantedEnd).reversed()));
        return wanted.stream().limit(HeadBoard.SPOTS).map(entry -> new HeadBoard.Entry(entry.uuid(), entry.name(), null)).toList();
    }

}
