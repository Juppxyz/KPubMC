package xyz.jupp.minecraft.cache;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import xyz.jupp.minecraft.database.TeamBlockCollection;
import xyz.jupp.minecraft.database.TeamCollection;

public class TeamBlockCacheObject {

    private double x;
    private double y;
    private double z;

    private Location location;
    private TeamBlockCollection teamBlockCollection;
    private String teamID;
    private boolean isActive = false;


    TeamBlockCacheObject(String teamID, double x,double y,double z, boolean isActive) {
        this.location = new Location(Bukkit.getWorld("world"), x, y, z);
        this.teamID = teamID;
        this.teamBlockCollection = new TeamBlockCollection(teamID);
        this.isActive = isActive;
    }

    private void updateLocation(double x, double y, double z) {
        this.location = new Location(Bukkit.getWorld("world"), x, y, z);
        teamBlockCollection.updateTeamBlockLocation(x, y, z);
    }

    public void placeTeamBlock(double x, double y, double z) {
        updateLocation(x,y,z);
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean isActive) {
        this.isActive = isActive;
        teamBlockCollection.setActiveState(isActive);
        if (!isActive) {
            TeamCollection teamCollection = new TeamCollection(getTeamID());
            teamCollection.changeTeamPoints(15);
        }
    }

    private TeamBlockCollection getTeamBlockCollection() {
        return teamBlockCollection;
    }

    public Location getLocation() {
        return this.location;
    }

    public String getTeamID() {
        return teamID;
    }
}
