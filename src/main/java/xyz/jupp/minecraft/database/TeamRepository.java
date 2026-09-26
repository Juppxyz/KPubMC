package xyz.jupp.minecraft.database;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Tables 'teams' and 'team_members'. Stateless, every method is blocking.
 * points are only changed relatively in SQL and can never become negative.
 */
public final class TeamRepository {

    private TeamRepository() {}

    public record TeamMember(UUID uuid, String role, String nickname) {
        public static TeamMember of(@NotNull Player player, @NotNull String role) {
            return new TeamMember(player.getUniqueId(), role, player.getName());
        }
    }

    public record TeamData(String teamID, String name, @Nullable String color, UUID owner, int points, int level,
                           boolean zonePvP, boolean zoneMobDamage, boolean zoneInteract, List<TeamMember> members) {}

    private static Logger log() {
        return Main.getInstance().getSLF4JLogger();
    }

    private static TeamMember mapMember(ResultSet row) throws SQLException {
        return new TeamMember(row.getObject("uuid", UUID.class), row.getString("role"), row.getString("nickname"));
    }


    public static @Nullable TeamData getTeam(@NotNull String teamID) {
        return Database.withConnection(connection -> {
            List<TeamMember> members = Database.query(connection,
                    "SELECT uuid, role, nickname FROM team_members WHERE team_id = ?", TeamRepository::mapMember, teamID);
            return Database.queryOne(connection, "SELECT * FROM teams WHERE team_id = ?", row -> new TeamData(
                    row.getString("team_id"),
                    row.getString("name"),
                    row.getString("color"),
                    row.getObject("owner_uuid", UUID.class),
                    row.getInt("points"),
                    row.getInt("level"),
                    row.getBoolean("zone_pvp"),
                    row.getBoolean("zone_mob_damage"),
                    row.getBoolean("zone_interact"),
                    members), teamID);
        });
    }

    /** Creates the team with its owner and assigns the owner to it, all in one transaction. Returns the new team id. */
    public static String createNewTeam(@NotNull Player owner, @NotNull String teamName, @Nullable String teamColor) {
        String teamID = UUID.randomUUID().toString().replace("-", "");
        Database.inTransaction(connection -> {
            Database.update(connection, "INSERT INTO teams (team_id, name, color, owner_uuid) VALUES (?, ?, ?, ?)",
                    teamID, teamName, teamColor, owner.getUniqueId());
            insertMember(connection, teamID, TeamMember.of(owner, "owner"));
            Database.update(connection, "UPDATE players SET team_id = ? WHERE uuid = ?", teamID, owner.getUniqueId());
            return null;
        });
        log().info("create new team {} ({})", teamName, teamID);
        return teamID;
    }

    /** Team ids ordered by points, highest first, with their points. */
    public static List<RankedTeam> getRanking() {
        return Database.query("SELECT team_id, points FROM teams ORDER BY points DESC",
                row -> new RankedTeam(row.getString("team_id"), row.getInt("points")));
    }

    public record RankedTeam(String teamID, int points) {}


    /* members */

    public static void addMember(@NotNull String teamID, @NotNull TeamMember member) {
        Database.withConnection(connection -> insertMember(connection, teamID, member));
        log().info("add player {} to team {}", member.nickname(), teamID);
    }

    private static int insertMember(Connection connection, String teamID, TeamMember member) throws SQLException {
        return Database.update(connection, """
                INSERT INTO team_members (team_id, uuid, role, nickname) VALUES (?, ?, ?, ?)
                ON CONFLICT (team_id, uuid) DO UPDATE SET role = EXCLUDED.role, nickname = EXCLUDED.nickname""",
                teamID, member.uuid(), member.role(), member.nickname());
    }

    public static void removeMember(@NotNull String teamID, @NotNull UUID uuid) {
        Database.update("DELETE FROM team_members WHERE team_id = ? AND uuid = ?", teamID, uuid);
        log().info("remove player {} from team {}", uuid, teamID);
    }

    public static void setMemberRole(@NotNull String teamID, @NotNull UUID uuid, @NotNull String role) {
        Database.update("UPDATE team_members SET role = ? WHERE team_id = ? AND uuid = ?", role, teamID, uuid);
        log().info("change role from {} to {} [{}]", uuid, role, teamID);
    }

    /** Toggles member/vice directly in the database (no cache involved) and returns the new role. */
    public static String toggleMemberRole(@NotNull String teamID, @NotNull UUID uuid) {
        String newRole = Database.queryOne("""
                UPDATE team_members SET role = CASE WHEN role = 'member' THEN 'vice' ELSE 'member' END
                WHERE team_id = ? AND uuid = ? AND role <> 'owner' RETURNING role""",
                row -> row.getString(1), teamID, uuid);
        if (newRole == null) return "member";
        log().info("change role from {} to {} [{}]", uuid, newRole, teamID);
        return newRole;
    }


    /* team points: reads return 0 for unknown teams, mutations log exactly one line */

    public static int getTeamPoints(@Nullable String teamID) {
        if (teamID == null) return 0;
        Integer points = Database.queryOne("SELECT points FROM teams WHERE team_id = ?", row -> row.getInt(1), teamID);
        return points == null ? 0 : points;
    }

    /** Adds delta without a check; a negative delta stops at 0. false if the team does not exist. */
    public static boolean addTeamPoints(@Nullable String teamID, int delta) {
        if (teamID == null) return false;
        Integer points = Database.queryOne("UPDATE teams SET points = GREATEST(points + ?, 0) WHERE team_id = ? RETURNING points",
                row -> row.getInt(1), delta, teamID);
        if (points == null) {
            log().warn("teamPoints {} {} failed: team not found", teamID, signed(delta));
            return false;
        }
        log().info("teamPoints {} {} -> {}", teamID, signed(delta), points);
        return true;
    }

    /** Withdraws amount only if the team has at least amount points. */
    public static boolean tryWithdrawTeamPoints(@Nullable String teamID, int amount) {
        if (teamID == null) return false;
        Integer points = Database.queryOne("UPDATE teams SET points = points - ? WHERE team_id = ? AND points >= ? RETURNING points",
                row -> row.getInt(1), amount, teamID, amount);
        if (points == null) return false;
        log().info("teamPoints {} {} -> {}", teamID, signed(-amount), points);
        return true;
    }

    /**
     * Subtracts amount but never goes below 0, atomically.
     * Returns the points the team had before (0 if the team does not exist).
     */
    public static int withdrawTeamPointsFloored(@NotNull String teamID, int amount) {
        Integer previousPoints = Database.inTransaction(connection -> {
            Integer before = Database.queryOne(connection, "SELECT points FROM teams WHERE team_id = ? FOR UPDATE",
                    row -> row.getInt(1), teamID);
            if (before == null) return null;
            Database.update(connection, "UPDATE teams SET points = GREATEST(points - ?, 0) WHERE team_id = ?", amount, teamID);
            return before;
        });
        if (previousPoints == null) {
            log().warn("teamPoints {} {} failed: team not found", teamID, signed(-amount));
            return 0;
        }
        log().info("teamPoints {} {} -> {}", teamID, signed(-amount), Math.max(0, previousPoints - amount));
        return previousPoints;
    }


    /* level and area options */

    public static void incTeamLevel(@NotNull String teamID) {
        Database.update("UPDATE teams SET level = level + 1 WHERE team_id = ?", teamID);
        log().info("update team-level for {}", teamID);
    }

    public static void decTeamLevel(@NotNull String teamID) {
        Database.update("UPDATE teams SET level = level - 1 WHERE team_id = ? AND level > 0", teamID);
        log().info("update team-level for {}", teamID);
    }

    /** Writes the given value; true if the team exists. */
    public static boolean setAreaOption(@NotNull String teamID, @NotNull AreaOptionsEnum areaOption, boolean value) {
        String column = switch (areaOption) {
            case INTERACTION  -> "zone_interact";
            case PVP          -> "zone_pvp";
            case MOB_GRIEFING -> "zone_mob_damage";
        };
        boolean matched = Database.update("UPDATE teams SET " + column + " = ? WHERE team_id = ?", value, teamID) > 0;
        log().info("set '{}' to {} for {}", column, value, teamID);
        return matched;
    }

    public static void resetAreaOptions(@NotNull String teamID) {
        Database.update("UPDATE teams SET zone_interact = TRUE, zone_pvp = TRUE, zone_mob_damage = TRUE WHERE team_id = ?", teamID);
        log().info("reset area options for {}", teamID);
    }


    private static String signed(int value) {
        return String.format("%+d", value);
    }

}
