package xyz.jupp.minecraft.cache;

import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerCollection;

public class PlayerCacheObject {

    // knowledge variables
    private String teamID;
    private Player player;
    private boolean teamInvites;
    private PlayerCollection playerCollection;
    private TeamCacheObject teamCacheObject = null;

    private boolean jail = false;
    private long jailEnd = 0;
    private boolean isWanted = false;


    PlayerCacheObject(@NotNull Player player){
        this.playerCollection = new PlayerCollection(player);
        this.player = player;
        initPlayerObject();
    }


    private void initPlayerObject() {
        Document document = getPlayerCollection().getPlayerDocument();
        this.teamID = document.getString("teamID");
        if (teamID != null) {
            this.teamCacheObject = TeamCache.getTeam(teamID);
        }
        this.teamInvites = document.getBoolean("teamInvites");
        this.jail = document.getBoolean("jail");
        this.jailEnd = document.getLong("jailEnd");
        this.isWanted = document.getBoolean("isWanted");
    }


    // new added in 2023 version
    public String getTeamColor() {
        if (teamID == null) return "§a";
        return getTeamCacheObject().getTeamColor();
    }


    public TeamCacheObject getTeamCacheObject(){
        return this.teamCacheObject;
    }


    public boolean changeTeamInvite() {
        teamInvites = !teamInvites;
        return playerCollection.changeTeamInvite();
    }


    public void changeTeamID(String id) {
        this.teamID = id;
        if (teamID != null) {
            this.teamCacheObject = TeamCache.getTeam(id);
        }else {
            this.teamCacheObject = null;
        }
        getPlayerCollection().changeTeamID(id);
    }


    public void updatePlayer() {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            Document document = new PlayerCollection(this.player).getPlayerDocument();
            String dbTeamID = document.getString("teamID");
            boolean dbTeamInvites = document.getBoolean("teamInvites");
            this.teamID = dbTeamID;
            this.teamInvites = dbTeamInvites;

            if (getTeamID() == null || getTeamCacheObject() == null) {
                getPlayer().setDisplayName("§a" + getPlayer().getName());
                getPlayer().setPlayerListName("§a" + getPlayer().getName());
                return;
            }

            if (getTeamCacheObject().getTeamOwner().equals(getPlayer().getUniqueId().toString())) {
                getPlayer().setPlayerListName(getTeamCacheObject().getTeamColor() + "§l" + getPlayer().getName());
                getPlayer().setDisplayName(getTeamCacheObject().getTeamColor() + "§l" + getPlayer().getName());
                return;
            }

            if (getTeamCacheObject().getTeamVices().contains(getPlayer().getUniqueId().toString())) {
                getPlayer().setPlayerListName(getTeamCacheObject().getTeamColor() + "§o" + getPlayer().getName());
                getPlayer().setDisplayName(getTeamCacheObject().getTeamColor() + "§o" + getPlayer().getName());
                return;
            }

            getPlayer().setPlayerListName(getTeamCacheObject().getTeamColor() + getPlayer().getName());
            getPlayer().setDisplayName(getTeamCacheObject().getTeamColor() + getPlayer().getName());

        });
    }




    // new added in 2025 (jail)
    public void setJail(boolean jail, int hours) {
        this.jail = jail;
        long now = System.currentTimeMillis();
        long futureMillis = now + (hours * 60L * 60L * 1000L);
        this.jailEnd = futureMillis;
        playerCollection.setJail(jail, futureMillis);
    }
    public void unsetJail(boolean isEscaped) {
        this.jail = false;
        if (isEscaped) {
            long now = System.currentTimeMillis();
            long futureMillis = now + (72L * 60L * 60L * 1000L);
            this.jailEnd = futureMillis;
        }else {
            this.jailEnd = 0L;
        }
        this.isWanted = isEscaped;
        playerCollection.unsetJail(this.jailEnd);
    }
    public boolean isJail() {
        return jail;
    }
    public long getJailEnd() {
        return jailEnd;
    }
    public boolean isWanted() {
        return isWanted;
    }
    public void setWanted(boolean wanted) {
        isWanted = wanted;
        playerCollection.setIsWanted(wanted);
    }



    // Getter
    public PlayerCollection getPlayerCollection() {
        return playerCollection;
    }

    public boolean isTeamInvites() {
        return teamInvites;
    }

    public Player getPlayer() {
        return player;
    }

    public String getTeamID() {
        return teamID;
    }
}
