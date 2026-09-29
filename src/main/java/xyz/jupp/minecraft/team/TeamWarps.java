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
 * One warp per team (table team_warps), shown in the /warp menu to the team's members. Owner and vices set it from
 * WARP_LEVEL on and pay it from the team treasury. The cache is loaded in onEnable; changes block (database).
 */
public final class TeamWarps {

    private TeamWarps() {}

    public static final int SET_COST = 5_000;
    public static final int MOVE_COST = 500;

    public record Warp(String world, double x, double y, double z) {
        public @Nullable Location toLocation() {
            World target = Bukkit.getWorld(world);
            return target == null ? null : new Location(target, x, y, z);
        }
    }

    public enum Outcome { OK, LEVEL, NOT_ALLOWED, INSUFFICIENT_FUNDS }

    public record Result(Outcome outcome, int cost) {}

    private static final ConcurrentHashMap<String, Warp> warps = new ConcurrentHashMap<>();

    /** Blocking, in onEnable. */
    public static int load() {
        warps.clear();
        Database.query("SELECT team_id, world, x, y, z FROM team_warps", row -> {
            warps.put(row.getString(1), new Warp(row.getString(2), row.getDouble(3), row.getDouble(4), row.getDouble(5)));
            return null;
        });
        return warps.size();
    }

    public static @Nullable Warp get(@Nullable String teamID) {
        return teamID == null ? null : warps.get(teamID);
    }

    /** Sets or moves the team warp to the location; the price comes from the team treasury. */
    public static Result set(@NotNull TeamCacheObject team, @NotNull UUID actor, @NotNull Location location) {
        if (team.getLevel() < Teams.WARP_LEVEL) return new Result(Outcome.LEVEL, 0);
        if (!Teams.role(team, actor).canManage()) return new Result(Outcome.NOT_ALLOWED, 0);
        String teamID = team.getTeamID();
        Warp warp = new Warp(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        Result result = Database.inTransaction(connection -> {
            // the team row first: two clicks at once pay the right price once each
            Database.queryOne(connection, "SELECT 1 FROM teams WHERE team_id = ? FOR UPDATE", row -> 1, teamID);
            boolean exists = Database.queryOne(connection, "SELECT 1 FROM team_warps WHERE team_id = ?", row -> 1, teamID) != null;
            int cost = exists ? MOVE_COST : SET_COST;
            if (!TeamBank.pay(connection, teamID, actor, exists ? TeamBank.WARP_MOVE : TeamBank.WARP_SET, cost)) {
                return new Result(Outcome.INSUFFICIENT_FUNDS, cost);
            }
            Database.update(connection, """
                    INSERT INTO team_warps (team_id, world, x, y, z) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (team_id) DO UPDATE SET world = EXCLUDED.world, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z""",
                    teamID, warp.world(), warp.x(), warp.y(), warp.z());
            return new Result(Outcome.OK, cost);
        });
        if (result.outcome() == Outcome.OK) {
            warps.put(teamID, warp);
            Main.getInstance().getSLF4JLogger().info("team warp of {} set to {} {} {} {} by {}", teamID, warp.world(),
                    Math.round(warp.x()), Math.round(warp.y()), Math.round(warp.z()), actor);
        }
        return result;
    }

    /** Owner or vice: removes the team warp (no money back). */
    public static boolean remove(@NotNull TeamCacheObject team, @NotNull UUID actor) {
        if (!Teams.role(team, actor).canManage()) return false;
        Database.update("DELETE FROM team_warps WHERE team_id = ?", team.getTeamID());
        boolean removed = warps.remove(team.getTeamID()) != null;
        if (removed) Main.getInstance().getSLF4JLogger().info("team warp of {} removed by {}", team.getTeamID(), actor);
        return removed;
    }

}
