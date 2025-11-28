package xyz.jupp.minecraft.cache;

import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.exceptions.TeamNotExistException;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;
import xyz.jupp.minecraft.utils.MemberListDoc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TeamCacheObject {

    private String          teamID;
    private String          teamName;
    private String          teamColor;
    private List<Document>  membersList;
    private TeamCollection  teamCollection;
    private int             level;
    private boolean         zoneOptionPvP;
    private boolean         zoneOptionMobDamage;
    private boolean         zoneOptionInteract;


    // roles
    private String              teamOwner;
    private ArrayList<String>   teamVices = new ArrayList<>(10);

    TeamCacheObject(@NotNull String teamID) throws TeamNotExistException {
        this.teamCollection = new TeamCollection(teamID);
        if (!teamCollection.existTeam()) throw new TeamNotExistException(String.format("the team with id %s doesn't exist", teamID));
        this.teamID = teamID;
        initTeamCacheObject();
    }

    private void initTeamCacheObject() {
        Document document = getTeamCollection().getTeamDocument();
        this.teamName = document.getString("teamName");
        this.teamColor = document.getString("teamColor");
        this.teamOwner = document.getString("teamOwner");
        this.membersList = (List<Document>) document.get("members");
        this.level = document.getInteger("level");
        if (!membersList.isEmpty()) {
            for (Document doc : membersList) {
                if (doc.getString("role").equals("vice")){
                    teamVices.add(doc.getString("uuid"));
                }
            }
        }
        this.zoneOptionPvP = document.getBoolean("zoneOptionPvP");
        this.zoneOptionMobDamage = document.getBoolean("zoneOptionMobDamage");
        this.zoneOptionInteract = document.getBoolean("zoneOptionInteract");
    }

    public void addPlayerToMemberList(@NotNull Player player) {
        membersList.add(MemberListDoc.getDoc(player));
        getTeamCollection().addMemberToTeam(player);
    }

    public void removePlayerFromMemberList(@NotNull Player player) {
        for (Document document : membersList) {
            if (Objects.equals(document.getString("uuid"), player.getUniqueId().toString())) {
                membersList.remove(document);
                getTeamCollection().removeMemberFromTeam(player);
                teamVices.remove(player.getUniqueId().toString());
                return;
            }
        }

    }

    public String changePlayerTeamRole(@NotNull Player player) {
        boolean isVice = false;
        if (getTeamVices().contains(player.getUniqueId().toString())){
            getTeamVices().remove(player.getUniqueId().toString());
        }else {
            isVice = true;
            getTeamVices().add(player.getUniqueId().toString());
        }
        Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "change role from §a" + player.getUniqueId() + "§f(§a" + isVice + "§f) [" + getTeamID() + "]");
        return getTeamCollection().changeRoleFromMember(player);
    }

    public void upgradeTeamLevel(int cost) {
        this.level++;
        getTeamCollection().incTeamLevel();
        getTeamCollection().changeTeamPoints(cost);
    }

    public boolean changeAreaSettings(@NotNull AreaOptionsEnum areaOption) {
        boolean dbResult = getTeamCollection().changeAreaSettings(areaOption);
        if (dbResult) {
            if (areaOption == AreaOptionsEnum.PVP) {
                this.zoneOptionPvP = !isZoneOptionPvP();
            }else if (areaOption == AreaOptionsEnum.INTERACTION) {
                this.zoneOptionInteract = !isZoneOptionInteract();
            }else if (areaOption == AreaOptionsEnum.MOB_GRIEFING) {
                this.zoneOptionMobDamage = !isZoneOptionMobDamage();
            }
        }
        return dbResult;
    }


    // Getter
    public TeamCollection getTeamCollection() {
        return teamCollection;
    }

    public String getTeamColor() {
        return "" + teamColor;
    }

    public ArrayList<String> getTeamVices() {
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
