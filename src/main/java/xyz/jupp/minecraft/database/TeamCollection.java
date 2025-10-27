package xyz.jupp.minecraft.database;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.MemberListDoc;

import java.util.*;

import static com.mongodb.client.model.Filters.eq;

public class TeamCollection {

    private static final MongoCollection<Document> teamsCollection = MongoDB.getInstance().getKpubMC().getCollection("teams");
    private String teamID;

    public TeamCollection(@NotNull String teamID) {
        this.teamID = teamID;
    }


    public boolean existTeam() {
        Bson filter = eq("teamID", getTeamID());
        return teamsCollection.find(filter).first() != null;
    }

    public Document getTeamDocument() {
        Bson filter = eq("teamID", getTeamID());
        return teamsCollection.find(filter).first();
    }

    public String createNewTeam(@NotNull Player owner, @NotNull String teamName, @Nullable String teamColor) {
        Document document = new Document("teamOwner", owner.getUniqueId().toString());
        String uuid = UUID.randomUUID().toString().replace("-", "");
        ArrayList<Document> memberList = new ArrayList<>(1);
        memberList.add(MemberListDoc.getDoc(owner, "owner"));
        document.append("teamID", uuid);
        document.append("teamName", teamName);
        document.append("teamColor", teamColor);
        document.append("teamPoints", 0);
        document.append("members", memberList);
        document.append("level", 1);
        document.append("zoneOptionPvP", true);
        document.append("zoneOptionMobDamage", true);
        document.append("zoneOptionInteract", true);
        teamsCollection.insertOne(document);
        Logger.console(String.format("create new team %s (%s)", teamName, teamID));
        Bukkit.broadcastMessage(Main.getChatPrefix() + "§fDas Team " + teamColor + teamName + " §fwurde von §6" + owner.getName() + " §fgegründet!");
        return uuid;
    }

    public String changeRoleFromMember(@NotNull Player player) {
        Bson filter = eq("teamID", getTeamID());
        List<Document> membersList = (List<Document>) teamsCollection.find(filter).into(new ArrayList<>()).get(0).get("members");
        String newRole = "member";
        for (Document memberDoc : membersList){
            if (memberDoc.getString("uuid").equals(player.getUniqueId().toString())) {
                newRole = (Objects.equals(memberDoc.getString("role"), "member")) ? "vice" : "member";
                memberDoc.replace("role", newRole);
                teamsCollection.findOneAndUpdate(filter, new Document("$set", new Document("members", membersList)));
            }
        }
        return newRole;
    }

    public void removeMemberFromTeam(@NotNull Player player) {
        Bson filter = eq("teamID", getTeamID());
        List<Document> membersList = (List<Document>) teamsCollection.find(filter).into(new ArrayList<>()).get(0).get("members");
        if (!membersList.isEmpty()) {
            membersList.removeIf(member -> member.getString("uuid").equals(player.getUniqueId().toString()));
            teamsCollection.findOneAndUpdate(filter, new Document("$set", new Document("members", membersList)));
            Logger.console( "remove player " + player.getName() + " from team " + getTeamID());
        }
    }

    public void addMemberToTeam(@NotNull Player player){
        Bson filter = eq("teamID", getTeamID());
        List<Document> membersList = (List<Document>) teamsCollection.find(filter).into(new ArrayList<>()).get(0).get("members");

        membersList.add(MemberListDoc.getDoc(player));
        teamsCollection.findOneAndUpdate(filter, new Document("$set", new Document("members", membersList)));
        Logger.console("add player " + player.getName() + " to team " + getTeamID());
    }


    // new, added in 2023 version
    public void changeTeamPoints(int money) {
        Bson filter = eq("teamID", getTeamID());
        Document res = teamsCollection.findOneAndUpdate(filter, new Document("$set", new Document("teamPoints", money)));
        Logger.console("update team points [" + getTeamID()+  "] (" + res.getInteger("teamPoints") + ")");
    }

    public int getTeamPoints() {
        Bson filter = eq("teamID", getTeamID());
        return teamsCollection.find(filter).first().getInteger("teamPoints");
    }


    public static FindIterable<Document> getAllTeamDocumentsSorted(){
        FindIterable<Document> iterDoc = teamsCollection.find().sort(new Document("teamPoints", -1));
        return iterDoc;
    }


    public void incTeamLevel() {
        Bson filter = eq("teamID", getTeamID());
        teamsCollection.updateOne(filter, Updates.inc("level", 1));
        Logger.console("update team-level for " + getTeamID());
    }


    public boolean changeAreaSettings(@NotNull AreaOptionsEnum areaOption) {
        final String field = switch (areaOption) {
            case INTERACTION   -> "zoneOptionInteract";
            case PVP           -> "zoneOptionPvP";
            case MOB_GRIEFING  -> "zoneOptionMobDamage";
        };

        Bson filter = eq("teamID", getTeamID());

        List<Bson> update = List.of(
                new Document("$set", new Document(
                        field, new Document("$not", List.of(
                        new Document("$ifNull", List.of("$" + field, false))
                ))
                ))
        );

        UpdateResult res = teamsCollection.updateOne(filter, update);
        Logger.console("toggled '%s' for %s (matched=%d, modified=%d)"
                .formatted(field, getTeamID(), res.getMatchedCount(), res.getModifiedCount()));

        return res.getModifiedCount() > 0;
    }

    // Getter
    public String getTeamID() {
        return teamID;
    }


}
