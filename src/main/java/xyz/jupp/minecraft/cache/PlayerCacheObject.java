package xyz.jupp.minecraft.cache;

import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.UUID;

public class PlayerCacheObject {

    // knowledge variables (written by async tasks and the main thread, therefore volatile)
    private final UUID uuid;
    private volatile Player player;
    private volatile String teamID;
    private volatile boolean teamInvites;
    private volatile TeamCacheObject teamCacheObject = null;

    private volatile boolean jail = false;
    private volatile long jailEnd = 0;
    private volatile boolean isWanted = false;


    private PlayerCacheObject(@NotNull UUID uuid, @NotNull Document document) {
        this.uuid = uuid;
        this.teamID = document.getString("teamID");
        if (teamID != null) {
            this.teamCacheObject = TeamCache.getTeam(teamID);
        }
        this.teamInvites = document.getBoolean("teamInvites", false);
        this.jail = document.getBoolean("jail", false);
        this.jailEnd = document.get("jailEnd") instanceof Number number ? number.longValue() : 0L;
        this.isWanted = document.getBoolean("isWanted", false);
    }

    // Loads the player from the database (blocking). A missing document is created like on the first login.
    static PlayerCacheObject load(@NotNull UUID uuid) {
        Document document = PlayerCollection.getPlayerDocument(uuid);
        if (document == null) {
            PlayerCollection.createIfAbsent(uuid);
            document = PlayerCollection.getPlayerDocument(uuid);
            if (document == null) throw new IllegalStateException("no player document for " + uuid);
        }
        return new PlayerCacheObject(uuid, document);
    }

    void attach(@NotNull Player player) {
        if (this.player != player) this.player = player;
    }


    // new added in 2023 version
    public String getTeamColor() {
        TeamCacheObject team = getTeamCacheObject();
        if (teamID == null || team == null) return "§a";
        return team.getTeamColor();
    }


    public TeamCacheObject getTeamCacheObject(){
        return this.teamCacheObject;
    }


    public boolean changeTeamInvite() {
        boolean newValue;
        synchronized (this) {
            newValue = !teamInvites;
            teamInvites = newValue;
        }
        PlayerCollection.setTeamInvites(uuid, newValue);
        return newValue;
    }


    public void changeTeamID(String id) {
        this.teamID = id;
        if (id != null) {
            this.teamCacheObject = TeamCache.getTeam(id);
        }else {
            this.teamCacheObject = null;
        }
        PlayerCollection.changeTeamID(uuid, id);
    }


    // re-reads teamID/teamInvites from the database and sets the names on the main thread
    public void updatePlayer() {
        Tasks.async(() -> {
            Document document = PlayerCollection.getPlayerDocument(uuid);
            if (document == null) return;
            this.teamID = document.getString("teamID");
            this.teamInvites = document.getBoolean("teamInvites", false);

            Tasks.sync(() -> {
                Player player = getPlayer();
                if (player == null || !player.isOnline()) return;
                TeamCacheObject team = getTeamCacheObject();

                if (getTeamID() == null || team == null) {
                    player.setDisplayName("§a" + player.getName());
                    player.setPlayerListName("§a" + player.getName());
                    return;
                }

                if (team.getTeamOwner().equals(uuid.toString())) {
                    player.setPlayerListName(team.getTeamColor() + "§l" + player.getName());
                    player.setDisplayName(team.getTeamColor() + "§l" + player.getName());
                    return;
                }

                if (team.getTeamVices().contains(uuid.toString())) {
                    player.setPlayerListName(team.getTeamColor() + "§o" + player.getName());
                    player.setDisplayName(team.getTeamColor() + "§o" + player.getName());
                    return;
                }

                player.setPlayerListName(team.getTeamColor() + player.getName());
                player.setDisplayName(team.getTeamColor() + player.getName());
            });
        });
    }




    // new added in 2025 (jail)
    public void setJail(boolean jail, int hours) {
        this.jail = jail;
        long now = System.currentTimeMillis();
        long futureMillis = now + (hours * 60L * 60L * 1000L);
        this.jailEnd = futureMillis;
        PlayerCollection.setJail(uuid, jail, futureMillis);
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
        PlayerCollection.unsetJail(uuid, this.jailEnd);
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
        PlayerCollection.setIsWanted(uuid, wanted);
    }



    // Getter
    public boolean isTeamInvites() {
        return teamInvites;
    }

    public UUID getUuid() {
        return uuid;
    }

    // null if the player is offline and was never attached
    public @Nullable Player getPlayer() {
        Player attached = this.player;
        return attached != null ? attached : Bukkit.getPlayer(uuid);
    }

    public String getTeamID() {
        return teamID;
    }
}
