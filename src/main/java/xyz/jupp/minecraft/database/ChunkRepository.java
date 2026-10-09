package xyz.jupp.minecraft.database;

import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

import java.util.List;

/**
 * Table 'chunks' (claimed team chunks, one claim per chunk). Stateless, every method is blocking.
 */
public final class ChunkRepository {

    private ChunkRepository() {}

    public record ChunkData(String world, int x, int z, String teamID) {}

    public static void release(@NotNull String teamID, @NotNull String world, int x, int z) {
        Database.update("DELETE FROM chunks WHERE world = ? AND x = ? AND z = ? AND team_id = ?", world, x, z, teamID);
        Main.getInstance().getSLF4JLogger().info("released chunk {}:{}:{} of team {}", world, x, z, teamID);
    }

    public static List<ChunkData> getAll() {
        return Database.query("SELECT world, x, z, team_id FROM chunks",
                row -> new ChunkData(row.getString("world"), row.getInt("x"), row.getInt("z"), row.getString("team_id")));
    }

}
