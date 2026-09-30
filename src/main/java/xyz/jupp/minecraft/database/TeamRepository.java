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
                           boolean zonePvP, boolean zoneMobDamage, boolean zoneInteract, boolean zoneAlarm, List<TeamMember> members) {}

    private static Logger log() {
        return Main.getInstance().getSLF4JLogger();
    }

    private static TeamMember mapMember(ResultSet row) throws SQLException {
        return new TeamMember(row.getObject("uuid", UUID.class), row.getString("role"), row.getString("nickname"));
    }


    /** All teams with their members (two queries, the tables are small), for the cache at startup. */
    public static List<TeamData> loadAllTeams() {
        return Database.withConnection(connection -> {
            java.util.Map<String, List<TeamMember>> members = new java.util.HashMap<>();
            Database.query(connection, "SELECT team_id, uuid, role, nickname FROM team_members", row -> {
                members.computeIfAbsent(row.getString("team_id"), key -> new java.util.ArrayList<>()).add(mapMember(row));
                return null;
            });
            return Database.query(connection, "SELECT * FROM teams", row -> new TeamData(
                    row.getString("team_id"),
                    row.getString("name"),
                    row.getString("color"),
                    row.getObject("owner_uuid", UUID.class),
                    row.getInt("points"),
                    row.getInt("level"),
                    row.getBoolean("zone_pvp"),
                    row.getBoolean("zone_mob_damage"),
                    row.getBoolean("zone_interact"),
                    row.getBoolean("zone_alarm"),
                    members.getOrDefault(row.getString("team_id"), List.of())));
        });
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
                    row.getBoolean("zone_alarm"),
                    members), teamID);
        });
    }

    public enum Founding { OK, NO_MONEY, IN_TEAM }

    public record Founded(Founding outcome, @Nullable String teamID) {}

    /**
     * Creates the team, takes the price from the founder and makes them its owner, all in one transaction;
     * only for a player without a team who has the money.
     */
    public static Founded createNewTeam(@NotNull Player owner, @NotNull String teamName, @Nullable String teamColor, int price) {
        String teamID = UUID.randomUUID().toString().replace("-", "");
        Founded founded = Database.inTransaction(connection -> {
            // the player row is locked: a team invite at the same moment waits and then finds the player in a team
            Integer money = Database.queryOne(connection, "SELECT money FROM players WHERE uuid = ? AND team_id IS NULL FOR UPDATE",
                    row -> row.getInt(1), owner.getUniqueId());
            if (money == null) return new Founded(Founding.IN_TEAM, null);
            if (money < price) return new Founded(Founding.NO_MONEY, null);
            Database.update(connection, "INSERT INTO teams (team_id, name, color, owner_uuid) VALUES (?, ?, ?, ?)",
                    teamID, teamName, teamColor, owner.getUniqueId());
            insertMember(connection, teamID, TeamMember.of(owner, "owner"));
            Database.update(connection, "UPDATE players SET money = money - ?, team_id = ? WHERE uuid = ?", price, teamID, owner.getUniqueId());
            return new Founded(Founding.OK, teamID);
        });
        if (founded.outcome() == Founding.OK) log().info("create new team {} ({}), -{} for {}", teamName, teamID, price, owner.getUniqueId());
        return founded;
    }

    /** Team ids ordered by points, highest first, with their points. */
    public static List<RankedTeam> getRanking() {
        return Database.query("SELECT team_id, points FROM teams ORDER BY points DESC",
                row -> new RankedTeam(row.getString("team_id"), row.getInt("points")));
    }

    public record RankedTeam(String teamID, int points) {}


    /* members */

    /** Adds the player to the team, only if they are in no team (checked in the same transaction). */
    public static boolean joinTeam(@NotNull String teamID, @NotNull TeamMember member) {
        boolean joined = Database.inTransaction(connection -> {
            if (Database.update(connection, "UPDATE players SET team_id = ? WHERE uuid = ? AND team_id IS NULL", teamID, member.uuid()) == 0) {
                return false;
            }
            insertMember(connection, teamID, member);
            return true;
        });
        if (joined) log().info("add player {} to team {}", member.nickname(), teamID);
        return joined;
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

    /** An offline member was removed: clears the player's team, only if it is still this one. */
    public static void clearPlayerTeam(@NotNull UUID uuid, @NotNull String teamID) {
        Database.update("UPDATE players SET team_id = NULL WHERE uuid = ? AND team_id = ?", uuid, teamID);
    }

    public static void setMemberRole(@NotNull String teamID, @NotNull UUID uuid, @NotNull String role) {
        Database.update("UPDATE team_members SET role = ? WHERE team_id = ? AND uuid = ?", role, teamID, uuid);
        log().info("change role from {} to {} [{}]", uuid, role, teamID);
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

    /* level and area options */

    /** Pays the price and raises the level in one statement; the new level, null if the points are not enough or the level changed. */
    public static @Nullable Integer upgradeLevel(@NotNull String teamID, int level, int price) {
        Integer newLevel = Database.queryOne("UPDATE teams SET points = points - ?, level = level + 1 WHERE team_id = ? AND level = ? AND points >= ? RETURNING level",
                row -> row.getInt(1), price, teamID, level, price);
        if (newLevel != null) log().info("team {} upgraded to level {} (-{} team points)", teamID, newLevel, price);
        return newLevel;
    }

    /** Writes the given value; true if the team exists. */
    public static boolean setAreaOption(@NotNull String teamID, @NotNull AreaOptionsEnum areaOption, boolean value) {
        String column = switch (areaOption) {
            case INTERACTION  -> "zone_interact";
            case PVP          -> "zone_pvp";
            case MOB_GRIEFING -> "zone_mob_damage";
            case ALARM        -> "zone_alarm";
        };
        boolean matched = Database.update("UPDATE teams SET " + column + " = ? WHERE team_id = ?", value, teamID) > 0;
        log().info("set '{}' to {} for {}", column, value, teamID);
        return matched;
    }



    private static String signed(int value) {
        return String.format("%+d", value);
    }

}
