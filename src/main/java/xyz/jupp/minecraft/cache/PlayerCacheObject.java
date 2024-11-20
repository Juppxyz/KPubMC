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
