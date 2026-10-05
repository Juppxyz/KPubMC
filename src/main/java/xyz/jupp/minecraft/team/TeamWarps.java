package xyz.jupp.minecraft.team;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.economy.TeamBank;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Up to two warps per team (table team_warps, number 1 and 2), shown in the /warp menu to the team and its partners.
 * Owner and vices set the first from WARP_LEVEL on, the second from SECOND_WARP_LEVEL on, and pay them from the team
 * treasury. The cache is loaded in onEnable; changes block (database).
 */
public final class TeamWarps {

    private TeamWarps() {}

    public static final int SET_COST = 5_000;
    public static final int MOVE_COST = 500;
    public static final int COUNT = 2;

    public record Warp(String world, double x, double y, double z) {
        public @Nullable Location toLocation() {
            World target = Bukkit.getWorld(world);
            return target == null ? null : new Location(target, x, y, z);
        }
    }

    public enum Outcome { OK, LEVEL, NOT_ALLOWED, INSUFFICIENT_FUNDS }

    public record Result(Outcome outcome, int cost) {}

    private record Key(String teamID, int number) {}

    private static final ConcurrentHashMap<Key, Warp> warps = new ConcurrentHashMap<>();

    /** Blocking, in onEnable. */
    public static int load() {
        warps.clear();
        Database.query("SELECT team_id, number, world, x, y, z FROM team_warps", row -> {
            warps.put(new Key(row.getString(1), row.getInt(2)), new Warp(row.getString(3), row.getDouble(4), row.getDouble(5), row.getDouble(6)));
            return null;
        });
        return warps.size();
    }

    /** The team level a warp number needs. */
    public static int requiredLevel(int number) {
        return number == 1 ? Teams.WARP_LEVEL : Teams.SECOND_WARP_LEVEL;
    }

    /** "Team-Warp" or "2. Team-Warp" */
    public static String label(int number) {
        return number == 1 ? "Team-Warp" : number + ". Team-Warp";
    }

    public static @Nullable Warp get(@Nullable String teamID, int number) {
        return teamID == null ? null : warps.get(new Key(teamID, number));
    }

    /** Sets or moves a team warp to the location; the price comes from the team treasury. */
    public static Result set(@NotNull TeamCacheObject team, @NotNull UUID actor, int number, @NotNull Location location) {
        if (number < 1 || number > COUNT || team.getLevel() < requiredLevel(number)) return new Result(Outcome.LEVEL, 0);
        if (!Teams.role(team, actor).canManage()) return new Result(Outcome.NOT_ALLOWED, 0);
        String teamID = team.getTeamID();
        Warp warp = new Warp(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        Result result = Database.inTransaction(connection -> {
            // the team row first: two clicks at once pay the right price once each
            Database.queryOne(connection, "SELECT 1 FROM teams WHERE team_id = ? FOR UPDATE", row -> 1, teamID);
            boolean exists = Database.queryOne(connection, "SELECT 1 FROM team_warps WHERE team_id = ? AND number = ?", row -> 1, teamID, number) != null;
            int cost = exists ? MOVE_COST : SET_COST;
            if (!TeamBank.pay(connection, teamID, actor, exists ? TeamBank.WARP_MOVE : TeamBank.WARP_SET, cost)) {
                return new Result(Outcome.INSUFFICIENT_FUNDS, cost);
            }
            Database.update(connection, """
                    INSERT INTO team_warps (team_id, number, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (team_id, number) DO UPDATE SET world = EXCLUDED.world, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z""",
                    teamID, number, warp.world(), warp.x(), warp.y(), warp.z());
            return new Result(Outcome.OK, cost);
        });
        if (result.outcome() == Outcome.OK) {
            warps.put(new Key(teamID, number), warp);
            Main.getInstance().getSLF4JLogger().info("team warp {} of {} set to {} {} {} {} by {}", number, teamID, warp.world(),
                    Math.round(warp.x()), Math.round(warp.y()), Math.round(warp.z()), actor);
        }
        return result;
    }

    /** A dissolved team: its warps are gone (the database removed them with the team). */
    public static void forgetTeam(@NotNull String teamID) {
        warps.keySet().removeIf(key -> key.teamID().equals(teamID));
    }

    /** Owner or vice: removes a team warp (no money back). */
    public static boolean remove(@NotNull TeamCacheObject team, @NotNull UUID actor, int number) {
        if (!Teams.role(team, actor).canManage()) return false;
        Database.update("DELETE FROM team_warps WHERE team_id = ? AND number = ?", team.getTeamID(), number);
        boolean removed = warps.remove(new Key(team.getTeamID(), number)) != null;
        if (removed) Main.getInstance().getSLF4JLogger().info("team warp {} of {} removed by {}", number, team.getTeamID(), actor);
        return removed;
    }

}
