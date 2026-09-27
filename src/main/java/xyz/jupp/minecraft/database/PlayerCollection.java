package xyz.jupp.minecraft.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import xyz.jupp.minecraft.Main;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;

/**
 * DAO for the collection 'player'. Stateless, every method is blocking (call it off the main thread where possible).
 * money is stored as Int32 and only changed with $inc, so parallel bookings cannot overwrite each other.
 */
public final class PlayerCollection {

    private PlayerCollection() {}

    public enum TransferResult { SUCCESS, INSUFFICIENT_FUNDS, TARGET_NOT_FOUND }

    private static final FindOneAndUpdateOptions RETURN_MONEY_AFTER = new FindOneAndUpdateOptions()
            .returnDocument(ReturnDocument.AFTER)
            .projection(include("money"));

    private static MongoCollection<Document> players() {
        return MongoDB.getInstance().getKpubMC().getCollection("player");
    }

    private static Logger log() {
        return Main.getInstance().getSLF4JLogger();
    }

    private static Bson byUuid(@NotNull UUID uuid) {
        return eq("uuid", uuid.toString());
    }


    /* create the player document if it does not exist yet (same fields and types as ever) */
    public static void createIfAbsent(@NotNull UUID uuid) {
        Document defaults = new Document("teamInvites", false)
                .append("cheatingKicks", 0)
                .append("loginStreak", 0)
                .append("jail", false)
                .append("jailEnd", 0L)
                .append("isWanted", false)
                .append("teamID", null)
                .append("money", 250);
        UpdateResult result = players().updateOne(byUuid(uuid), new Document("$setOnInsert", defaults), new UpdateOptions().upsert(true));
        if (result.getUpsertedId() != null) {
            log().info("create new player {} in database.", uuid);
        }
    }

    public static @Nullable Document getPlayerDocument(@NotNull UUID uuid) {
        return players().find(byUuid(uuid)).first();
    }


    public static void setTeamInvites(@NotNull UUID uuid, boolean teamInvites) {
        players().updateOne(byUuid(uuid), set("teamInvites", teamInvites));
        log().info("updated teamInvites from {} to {}", uuid, teamInvites);
    }

    public static void changeTeamID(@NotNull UUID uuid, @Nullable String teamID) {
        players().updateOne(byUuid(uuid), set("teamID", teamID));
        log().info("updated teamID from {} to {}", uuid, teamID);
    }


    /* money: reads return 0 for unknown players, mutations log exactly one line */

    public static int getMoney(@NotNull UUID uuid) {
        Document document = players().find(byUuid(uuid)).projection(include("money")).first();
        return document == null ? 0 : asInt(document.get("money"));
    }

    public static int getMoney(@NotNull Player player) {
        return getMoney(player.getUniqueId());
    }

    /** Adds delta (may be negative) without any balance check. false if the player has no document. */
    public static boolean addMoney(@NotNull UUID uuid, int delta) {
        Document updated = players().findOneAndUpdate(byUuid(uuid), inc("money", delta), RETURN_MONEY_AFTER);
        if (updated == null) {
            log().warn("money {} {} failed: player not found", uuid, signed(delta));
            return false;
        }
        log().info("money {} {} -> {}", uuid, signed(delta), updated.get("money"));
        return true;
    }

    public static boolean addMoney(@NotNull Player player, int delta) {
        return addMoney(player.getUniqueId(), delta);
    }

    /** Withdraws amount only if the balance is at least amount (same as the former 'money < amount' checks). */
    public static boolean tryWithdrawMoney(@NotNull UUID uuid, int amount) {
        Document updated = players().findOneAndUpdate(and(byUuid(uuid), gte("money", amount)), inc("money", -amount), RETURN_MONEY_AFTER);
        if (updated == null) return false;
        log().info("money {} {} -> {}", uuid, signed(-amount), updated.get("money"));
        return true;
    }

    public static boolean tryWithdrawMoney(@NotNull Player player, int amount) {
        return tryWithdrawMoney(player.getUniqueId(), amount);
    }

    /** Withdraws from the sender first, then credits the receiver; the sender is refunded if the receiver is missing. */
    public static TransferResult transferMoney(@NotNull UUID from, @NotNull UUID to, int amount) {
        if (!tryWithdrawMoney(from, amount)) return TransferResult.INSUFFICIENT_FUNDS;
        boolean credited;
        try {
            credited = addMoney(to, amount);
        } catch (RuntimeException e) {
            addMoney(from, amount);
            throw e;
        }
        if (!credited) {
            addMoney(from, amount);
            return TransferResult.TARGET_NOT_FOUND;
        }
        return TransferResult.SUCCESS;
    }


    /* jail (new added in 2025) */

    public static void setJail(@NotNull UUID uuid, boolean jail, long jailEnd) {
        players().updateOne(byUuid(uuid), new Document("$set", new Document("jail", jail).append("jailEnd", jailEnd)));
        log().info("set jail for {} until {}", uuid, jailEnd);
    }

    public static void unsetJail(@NotNull UUID uuid, long jailEnd) {
        players().updateOne(byUuid(uuid), new Document("$set", new Document("jail", false).append("jailEnd", jailEnd)));
        log().info("unset jail for {}", uuid);
    }

    public static void setIsWanted(@NotNull UUID uuid, boolean isWanted) {
        players().updateOne(byUuid(uuid), set("isWanted", isWanted));
        log().info("set wanted for {} to {}", uuid, isWanted);
    }

    public static List<Document> getWantedPlayers() {
        long now = System.currentTimeMillis();

        Bson filter = new Document("isWanted", true)
                .append("jailEnd", new Document("$gt", now));

        return players()
                .find(filter)
                .sort(new Document("jailEnd", -1))
                .into(new ArrayList<>());
    }


    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String signed(int value) {
        return String.format("%+d", value);
    }

}
