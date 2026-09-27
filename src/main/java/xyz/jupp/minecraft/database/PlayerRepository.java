package xyz.jupp.minecraft.database;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import xyz.jupp.minecraft.Main;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Table 'players'. Stateless, every method is blocking (call it off the main thread where possible).
 * money is only changed relatively in SQL, so parallel bookings cannot overwrite each other.
 */
public final class PlayerRepository {

    private PlayerRepository() {}

    public enum TransferResult { SUCCESS, INSUFFICIENT_FUNDS, TARGET_NOT_FOUND }

    public record PlayerData(UUID uuid, int money, @Nullable String teamID, boolean teamInvites,
                             boolean jail, long jailEnd, boolean isWanted) {}

    private static PlayerData map(ResultSet row) throws SQLException {
        return new PlayerData(
                row.getObject("uuid", UUID.class),
                row.getInt("money"),
                row.getString("team_id"),
                row.getBoolean("team_invites"),
                row.getBoolean("jail"),
                row.getLong("jail_end"),
                row.getBoolean("is_wanted"));
    }

    private static Logger log() {
        return Main.getInstance().getSLF4JLogger();
    }


    /* creates the player with the default values if it does not exist yet */
    public static void createIfAbsent(@NotNull UUID uuid) {
        if (Database.update("INSERT INTO players (uuid) VALUES (?) ON CONFLICT (uuid) DO NOTHING", uuid) > 0) {
            log().info("create new player {} in database.", uuid);
        }
    }

    /** Remembers the login for the economy measurement (active players). */
    public static void touch(@NotNull UUID uuid) {
        Database.update("UPDATE players SET last_seen = now() WHERE uuid = ?", uuid);
    }

    public static @Nullable PlayerData getPlayer(@NotNull UUID uuid) {
        return Database.queryOne("SELECT * FROM players WHERE uuid = ?", PlayerRepository::map, uuid);
    }


    public static void setTeamInvites(@NotNull UUID uuid, boolean teamInvites) {
        Database.update("UPDATE players SET team_invites = ? WHERE uuid = ?", teamInvites, uuid);
        log().info("updated teamInvites from {} to {}", uuid, teamInvites);
    }

    public static void changeTeamID(@NotNull UUID uuid, @Nullable String teamID) {
        Database.update("UPDATE players SET team_id = ? WHERE uuid = ?", teamID, uuid);
        log().info("updated teamID from {} to {}", uuid, teamID);
    }


    /* money: reads return 0 for unknown players, mutations log exactly one line */

    public static int getMoney(@NotNull UUID uuid) {
        Integer money = Database.queryOne("SELECT money FROM players WHERE uuid = ?", row -> row.getInt(1), uuid);
        return money == null ? 0 : money;
    }

    public static int getMoney(@NotNull Player player) {
        return getMoney(player.getUniqueId());
    }

    /** Adds delta without a balance check; a negative delta stops at 0. false if the player does not exist. */
    public static boolean addMoney(@NotNull UUID uuid, int delta) {
        Integer money = Database.queryOne("UPDATE players SET money = GREATEST(money + ?, 0) WHERE uuid = ? RETURNING money",
                row -> row.getInt(1), delta, uuid);
        if (money == null) {
            log().warn("money {} {} failed: player not found", uuid, signed(delta));
            return false;
        }
        log().info("money {} {} -> {}", uuid, signed(delta), money);
        return true;
    }

    public static boolean addMoney(@NotNull Player player, int delta) {
        return addMoney(player.getUniqueId(), delta);
    }

    /** Withdraws amount only if the balance is at least amount. */
    public static boolean tryWithdrawMoney(@NotNull UUID uuid, int amount) {
        Integer money = Database.queryOne("UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ? RETURNING money",
                row -> row.getInt(1), amount, uuid, amount);
        if (money == null) return false;
        log().info("money {} {} -> {}", uuid, signed(-amount), money);
        return true;
    }

    public static boolean tryWithdrawMoney(@NotNull Player player, int amount) {
        return tryWithdrawMoney(player.getUniqueId(), amount);
    }

    /** Moves money from one player to another in one transaction. */
    public static TransferResult transferMoney(@NotNull UUID from, @NotNull UUID to, int amount) {
        TransferResult result = Database.inTransaction(connection -> {
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?", amount, from, amount) == 0) {
                return TransferResult.INSUFFICIENT_FUNDS;
            }
            if (Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", amount, to) == 0) {
                connection.rollback();
                return TransferResult.TARGET_NOT_FOUND;
            }
            return TransferResult.SUCCESS;
        });
        if (result == TransferResult.SUCCESS) log().info("money {} -> {}: {}", from, to, amount);
        return result;
    }


    /* jail (new added in 2025) */

    public static void setJail(@NotNull UUID uuid, boolean jail, long jailEnd) {
        Database.update("UPDATE players SET jail = ?, jail_end = ? WHERE uuid = ?", jail, jailEnd, uuid);
        log().info("set jail for {} until {}", uuid, jailEnd);
    }

    public static void unsetJail(@NotNull UUID uuid, long jailEnd) {
        Database.update("UPDATE players SET jail = FALSE, jail_end = ? WHERE uuid = ?", jailEnd, uuid);
        log().info("unset jail for {}", uuid);
    }

    /** Wanted until the given time (the same end field the escape uses). */
    public static void setWantedUntil(@NotNull UUID uuid, long wantedEnd) {
        Database.update("UPDATE players SET is_wanted = TRUE, jail_end = ? WHERE uuid = ?", wantedEnd, uuid);
        log().info("set wanted for {} until {}", uuid, wantedEnd);
    }

    public static void setIsWanted(@NotNull UUID uuid, boolean isWanted) {
        Database.update("UPDATE players SET is_wanted = ? WHERE uuid = ?", isWanted, uuid);
        log().info("set wanted for {} to {}", uuid, isWanted);
    }

    /** Wanted players whose time has not run out yet, longest remaining time first. */
    public static List<PlayerData> getWantedPlayers() {
        return Database.query("SELECT * FROM players WHERE is_wanted AND jail_end > ? ORDER BY jail_end DESC",
                PlayerRepository::map, System.currentTimeMillis());
    }


    private static String signed(int value) {
        return String.format("%+d", value);
    }

}
