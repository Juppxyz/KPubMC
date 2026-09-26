package xyz.jupp.minecraft.database;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;
import xyz.jupp.minecraft.utils.MemberListDoc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;

/**
 * DAO for the collection 'teams'. Stateless, every method is blocking.
 * teamPoints are stored as Int32 and only changed atomically.
 */
public final class TeamCollection {

    private TeamCollection() {}

    private static final FindOneAndUpdateOptions RETURN_POINTS_AFTER = new FindOneAndUpdateOptions()
            .returnDocument(ReturnDocument.AFTER)
            .projection(include("teamPoints"));

    private static final FindOneAndUpdateOptions RETURN_POINTS_BEFORE = new FindOneAndUpdateOptions()
            .returnDocument(ReturnDocument.BEFORE)
            .projection(include("teamPoints"));

    private static MongoCollection<Document> teams() {
        return MongoDB.getInstance().getKpubMC().getCollection("teams");
    }

    private static Logger log() {
        return Main.getInstance().getSLF4JLogger();
    }

    private static Bson byTeamID(@NotNull String teamID) {
        return eq("teamID", teamID);
    }


    public static @Nullable Document getTeamDocument(@NotNull String teamID) {
        return teams().find(byTeamID(teamID)).first();
    }

    /* inserts the team and returns the new team id */
    public static String createNewTeam(@NotNull Player owner, @NotNull String teamName, @Nullable String teamColor) {
        Document document = new Document("teamOwner", owner.getUniqueId().toString());
        String teamID = UUID.randomUUID().toString().replace("-", "");
        ArrayList<Document> memberList = new ArrayList<>(1);
        memberList.add(MemberListDoc.getDoc(owner, "owner"));
        document.append("teamID", teamID);
        document.append("teamName", teamName);
        document.append("teamColor", teamColor);
        document.append("teamPoints", 0);
        document.append("members", memberList);
        document.append("level", 1);
        document.append("zoneOptionPvP", true);
        document.append("zoneOptionMobDamage", true);
        document.append("zoneOptionInteract", true);
        teams().insertOne(document);
        log().info("create new team {} ({})", teamName, teamID);
        return teamID;
    }

    public static FindIterable<Document> getAllTeamDocumentsSorted() {
        return teams().find().sort(new Document("teamPoints", -1));
    }


    /* members */

    public static void addMember(@NotNull String teamID, @NotNull Document memberDocument) {
        teams().updateOne(byTeamID(teamID), Updates.push("members", memberDocument));
        log().info("add player {} to team {}", memberDocument.getString("nickname"), teamID);
    }

    public static void removeMember(@NotNull String teamID, @NotNull UUID uuid) {
        teams().updateOne(byTeamID(teamID), Updates.pull("members", eq("uuid", uuid.toString())));
        log().info("remove player {} from team {}", uuid, teamID);
    }

    public static void setMemberRole(@NotNull String teamID, @NotNull UUID uuid, @NotNull String role) {
        teams().updateOne(and(byTeamID(teamID), eq("members.uuid", uuid.toString())), set("members.$.role", role));
        log().info("change role from {} to {} [{}]", uuid, role, teamID);
    }

    /** Toggles member/vice directly in the database (no cache involved) and returns the new role. */
    public static String toggleMemberRole(@NotNull String teamID, @NotNull UUID uuid) {
        String newRole = "member";
        Document team = teams().find(byTeamID(teamID)).projection(include("members")).first();
        if (team == null) return newRole;
        for (Document member : team.getList("members", Document.class, List.of())) {
            if (uuid.toString().equals(member.getString("uuid"))) {
                newRole = "member".equals(member.getString("role")) ? "vice" : "member";
                setMemberRole(teamID, uuid, newRole);
            }
        }
        return newRole;
    }


    /* team points: reads return 0 for unknown teams, mutations log exactly one line */

    public static int getTeamPoints(@Nullable String teamID) {
        if (teamID == null) return 0;
        Document document = teams().find(byTeamID(teamID)).projection(include("teamPoints")).first();
        return document == null ? 0 : asInt(document.get("teamPoints"));
    }

    /** Adds delta (may be negative) without any check. false if the team does not exist. */
    public static boolean addTeamPoints(@Nullable String teamID, int delta) {
        if (teamID == null) return false;
        Document updated = teams().findOneAndUpdate(byTeamID(teamID), inc("teamPoints", delta), RETURN_POINTS_AFTER);
        if (updated == null) {
            log().warn("teamPoints {} {} failed: team not found", teamID, signed(delta));
            return false;
        }
        log().info("teamPoints {} {} -> {}", teamID, signed(delta), updated.get("teamPoints"));
        return true;
    }

    /** Withdraws amount only if the team has at least amount points (same as the former 'teamPoints < amount' checks). */
    public static boolean tryWithdrawTeamPoints(@Nullable String teamID, int amount) {
        if (teamID == null) return false;
        Document updated = teams().findOneAndUpdate(and(byTeamID(teamID), gte("teamPoints", amount)), inc("teamPoints", -amount), RETURN_POINTS_AFTER);
        if (updated == null) return false;
        log().info("teamPoints {} {} -> {}", teamID, signed(-amount), updated.get("teamPoints"));
        return true;
    }

    /**
     * Subtracts amount but never goes below 0, in one atomic update.
     * Returns the points the team had before (0 if the team does not exist).
     */
    public static int withdrawTeamPointsFloored(@NotNull String teamID, int amount) {
        List<Bson> update = List.of(new Document("$set", new Document("teamPoints",
                new Document("$max", List.of(0, new Document("$subtract", List.of("$teamPoints", amount)))))));
        Document before = teams().findOneAndUpdate(byTeamID(teamID), update, RETURN_POINTS_BEFORE);
        if (before == null) {
            log().warn("teamPoints {} {} failed: team not found", teamID, signed(-amount));
            return 0;
        }
        int previousPoints = asInt(before.get("teamPoints"));
        log().info("teamPoints {} {} -> {}", teamID, signed(-amount), Math.max(0, previousPoints - amount));
        return previousPoints;
    }


    /* level and area options */

    public static void incTeamLevel(@NotNull String teamID) {
        teams().updateOne(byTeamID(teamID), inc("level", 1));
        log().info("update team-level for {}", teamID);
    }

    public static void decTeamLevel(@NotNull String teamID) {
        teams().updateOne(and(byTeamID(teamID), gt("level", 0)), inc("level", -1));
        log().info("update team-level for {}", teamID);
    }

    /** Writes the given value; true if the team exists. */
    public static boolean setAreaOption(@NotNull String teamID, @NotNull AreaOptionsEnum areaOption, boolean value) {
        String field = getAreaOptionField(areaOption);
        boolean matched = teams().updateOne(byTeamID(teamID), set(field, value)).getMatchedCount() > 0;
        log().info("set '{}' to {} for {}", field, value, teamID);
        return matched;
    }

    public static void resetAreaOptions(@NotNull String teamID) {
        teams().updateOne(byTeamID(teamID), Updates.combine(
                set("zoneOptionInteract", true),
                set("zoneOptionPvP", true),
                set("zoneOptionMobDamage", true)
        ));
        log().info("reset area options for {}", teamID);
    }

    private static String getAreaOptionField(@NotNull AreaOptionsEnum areaOption) {
        return switch (areaOption) {
            case INTERACTION   -> "zoneOptionInteract";
            case PVP           -> "zoneOptionPvP";
            case MOB_GRIEFING  -> "zoneOptionMobDamage";
        };
    }


    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String signed(int value) {
        return String.format("%+d", value);
    }

}
