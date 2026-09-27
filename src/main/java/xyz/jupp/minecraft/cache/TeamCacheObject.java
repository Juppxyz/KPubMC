package xyz.jupp.minecraft.cache;

import org.bson.Document;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;
import xyz.jupp.minecraft.utils.MemberListDoc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class TeamCacheObject {

    private final String            teamID;
    private final String            teamName;
    private final String            teamColor;
    private final List<Document>    membersList;
    private volatile int            level;
    private volatile boolean        zoneOptionPvP;
    private volatile boolean        zoneOptionMobDamage;
    private volatile boolean        zoneOptionInteract;


    // roles
    private final String            teamOwner;
    private final List<String>      teamVices = new CopyOnWriteArrayList<>();

    // Throws a RuntimeException if the document misses required fields, TeamCache treats the team as unknown then.
    TeamCacheObject(@NotNull String teamID, @NotNull Document document) {
        this.teamID = teamID;
        this.teamName = document.getString("teamName");
        this.teamColor = document.getString("teamColor");
        this.teamOwner = document.getString("teamOwner");
        this.membersList = new CopyOnWriteArrayList<>(document.getList("members", Document.class));
        this.level = document.getInteger("level");
        for (Document doc : membersList) {
            if (doc.getString("role").equals("vice")){
                teamVices.add(doc.getString("uuid"));
            }
        }
        this.zoneOptionPvP = document.getBoolean("zoneOptionPvP");
        this.zoneOptionMobDamage = document.getBoolean("zoneOptionMobDamage");
        this.zoneOptionInteract = document.getBoolean("zoneOptionInteract");
    }

    public void addPlayerToMemberList(@NotNull Player player) {
        Document memberDocument = MemberListDoc.getDoc(player);
        membersList.add(memberDocument);
        TeamCollection.addMember(teamID, memberDocument);
    }

    public void removePlayerFromMemberList(@NotNull Player player) {
        String uuid = player.getUniqueId().toString();
        if (membersList.removeIf(document -> uuid.equals(document.getString("uuid")))) {
            TeamCollection.removeMember(teamID, player.getUniqueId());
            teamVices.remove(uuid);
        }
    }

    // toggles member <-> vice in the cache and writes the resulting role
    public synchronized String changePlayerTeamRole(@NotNull UUID uuid) {
        String id = uuid.toString();
        boolean isVice = !teamVices.contains(id);
        if (isVice) {
            teamVices.add(id);
        } else {
            teamVices.remove(id);
        }
        String newRole = isVice ? "vice" : "member";
        TeamCollection.setMemberRole(teamID, uuid, newRole);
        return newRole;
    }

    // the points are withdrawn by the caller, holding this object's lock together with the level read
    public synchronized void upgradeTeamLevel() {
        this.level++;
        TeamCollection.incTeamLevel(teamID);
    }

    // the points are reset by the caller
    public synchronized void downgradeTeamLevel() {
        if (this.level > 0) this.level--;
        this.zoneOptionInteract = true;
        this.zoneOptionPvP = true;
        this.zoneOptionMobDamage = true;
        TeamCollection.resetAreaOptions(teamID);
        TeamCollection.decTeamLevel(teamID);
    }

    public synchronized boolean changeAreaSettings(@NotNull AreaOptionsEnum areaOption) {
        boolean newValue = switch (areaOption) {
            case PVP -> !zoneOptionPvP;
            case INTERACTION -> !zoneOptionInteract;
            case MOB_GRIEFING -> !zoneOptionMobDamage;
        };
        boolean teamExists = TeamCollection.setAreaOption(teamID, areaOption, newValue);
        if (teamExists) {
            switch (areaOption) {
                case PVP -> this.zoneOptionPvP = newValue;
                case INTERACTION -> this.zoneOptionInteract = newValue;
                case MOB_GRIEFING -> this.zoneOptionMobDamage = newValue;
            }
        }
        return teamExists;
    }


    // Getter
    public String getTeamColor() {
        return "" + teamColor;
    }

    public List<String> getTeamVices() {
        return teamVices;
    }

    public String getTeamName() {
        return teamName;
    }

    public String getTeamOwner() {
        return teamOwner;
    }

    public List<Document> getMembersList() {
        return membersList;
    }

    public String getTeamID() {
        return teamID;
    }

    public int getLevel() {return level;}

    public boolean isZoneOptionPvP() {
        return zoneOptionPvP;
    }

    public boolean isZoneOptionMobDamage() {
        return zoneOptionMobDamage;
    }

    public boolean isZoneOptionInteract() {
        return zoneOptionInteract;
    }
}
