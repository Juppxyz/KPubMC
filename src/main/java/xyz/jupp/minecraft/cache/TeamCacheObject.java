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
    private volatile boolean        zoneOptionAlarm;


    // roles
    private volatile String         teamOwner;
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
        this.zoneOptionAlarm = data.zoneAlarm();
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

    // the database has it already (Teams.ownerLeaves): the next one is boss, the old boss is out
    public synchronized void ownerChanged(@NotNull UUID newOwner, @NotNull UUID oldOwner) {
        this.teamOwner = newOwner.toString();
        teamVices.remove(newOwner.toString());
        membersList.removeIf(member -> member.uuid().equals(oldOwner));
        membersList.replaceAll(member -> member.uuid().equals(newOwner) ? new TeamMember(member.uuid(), "owner", member.nickname()) : member);
    }

    // points and level in one statement, the cache follows only when it went through
    public synchronized boolean upgrade(int price) {
        Integer newLevel = TeamRepository.upgradeLevel(teamID, level, price);
        if (newLevel == null) return false;
        this.level = newLevel;
        return true;
    }

    // the database has the lower level already (Teams.deathPenalty); options the level does not have go back to on
    public synchronized void levelDropped(int newLevel, boolean resetMobGriefing, boolean resetPvP, boolean resetInteraction) {
        this.level = newLevel;
        if (resetMobGriefing) this.zoneOptionMobDamage = true;
        if (resetPvP) this.zoneOptionPvP = true;
        if (resetInteraction) this.zoneOptionInteract = true;
    }

    public synchronized boolean changeAreaSettings(@NotNull AreaOptionsEnum areaOption) {
        boolean newValue = switch (areaOption) {
            case PVP -> !zoneOptionPvP;
            case INTERACTION -> !zoneOptionInteract;
            case MOB_GRIEFING -> !zoneOptionMobDamage;
            case ALARM -> !zoneOptionAlarm;
        };
        boolean teamExists = TeamRepository.setAreaOption(teamID, areaOption, newValue);
        if (teamExists) {
            switch (areaOption) {
                case PVP -> this.zoneOptionPvP = newValue;
                case INTERACTION -> this.zoneOptionInteract = newValue;
                case MOB_GRIEFING -> this.zoneOptionMobDamage = newValue;
                case ALARM -> this.zoneOptionAlarm = newValue;
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

    public boolean isZoneOptionAlarm() {
        return zoneOptionAlarm;
    }
}
