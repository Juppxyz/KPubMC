package xyz.jupp.minecraft.cache;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.TeamRepository;
import xyz.jupp.minecraft.database.TeamRepository.TeamData;
import xyz.jupp.minecraft.database.TeamRepository.TeamMember;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class TeamCacheObject {

    private final String            teamID;
    private final String            teamName;
    private final String            teamColor;
    private final List<TeamMember>  membersList;
    private volatile int            level;
    private volatile boolean        zoneOptionPvP;
    private volatile boolean        zoneOptionMobDamage;
    private volatile boolean        zoneOptionInteract;


    // roles
    private final String            teamOwner;
    private final List<String>      teamVices = new CopyOnWriteArrayList<>();

    TeamCacheObject(@NotNull TeamData data) {
        this.teamID = data.teamID();
        this.teamName = data.name();
        this.teamColor = data.color();
        this.teamOwner = data.owner().toString();
        this.membersList = new CopyOnWriteArrayList<>(data.members());
        this.level = data.level();
        for (TeamMember member : membersList) {
            if (member.role().equals("vice")) {
                teamVices.add(member.uuid().toString());
            }
        }
        this.zoneOptionPvP = data.zonePvP();
        this.zoneOptionMobDamage = data.zoneMobDamage();
        this.zoneOptionInteract = data.zoneInteract();
    }

    // the database has the member already (TeamRepository.joinTeam)
    void memberJoined(@NotNull TeamMember member) {
        membersList.add(member);
    }

    public void removePlayerFromMemberList(@NotNull Player player) {
        removeMember(player.getUniqueId());
    }

    // also for offline members; the player's own team_id is the caller's. Same monitor as the role change.
    public synchronized void removeMember(@NotNull UUID uuid) {
        if (membersList.removeIf(member -> member.uuid().equals(uuid))) {
            TeamRepository.removeMember(teamID, uuid);
            teamVices.remove(uuid.toString());
        }
    }

    // toggles member <-> vice in the cache and writes the resulting role; null if the player is not (any longer) a member
    public synchronized @Nullable String changePlayerTeamRole(@NotNull UUID uuid) {
        if (membersList.stream().noneMatch(member -> member.uuid().equals(uuid))) return null;
        String id = uuid.toString();
        boolean isVice = !teamVices.contains(id);
        if (isVice) {
            teamVices.add(id);
        } else {
            teamVices.remove(id);
        }
        String newRole = isVice ? "vice" : "member";
        TeamRepository.setMemberRole(teamID, uuid, newRole);
        return newRole;
    }

    // points and level in one statement, the cache follows only when it went through
    public synchronized boolean upgrade(int price) {
        Integer newLevel = TeamRepository.upgradeLevel(teamID, level, price);
        if (newLevel == null) return false;
        this.level = newLevel;
        return true;
    }

    // the points are reset by the caller
    public synchronized void downgradeTeamLevel() {
        if (this.level > 0) this.level--;
        this.zoneOptionInteract = true;
        this.zoneOptionPvP = true;
        this.zoneOptionMobDamage = true;
        TeamRepository.resetAreaOptions(teamID);
        TeamRepository.decTeamLevel(teamID);
    }

    public synchronized boolean changeAreaSettings(@NotNull AreaOptionsEnum areaOption) {
        boolean newValue = switch (areaOption) {
            case PVP -> !zoneOptionPvP;
            case INTERACTION -> !zoneOptionInteract;
            case MOB_GRIEFING -> !zoneOptionMobDamage;
        };
        boolean teamExists = TeamRepository.setAreaOption(teamID, areaOption, newValue);
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

    public List<TeamMember> getMembersList() {
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
