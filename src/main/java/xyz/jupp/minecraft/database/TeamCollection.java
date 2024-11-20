package xyz.jupp.minecraft.database;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.MemberListDoc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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


    public void incTeamBlockAlreadyPurchased() {
        Bson filter = eq("teamID", getTeamID());
        teamsCollection.updateOne(filter, Updates.inc("alreadyPurchased", 1));
        Logger.console("update alreadyPurchased teamblock for " + getTeamID());
    }
    
    // Getter
    public String getTeamID() {
        return teamID;
    }


}
