package xyz.jupp.minecraft.cache;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.database.PlayerRepository.PlayerData;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.UUID;

public class PlayerCacheObject {

    // knowledge variables (written by async tasks and the main thread, therefore volatile)
    private final UUID uuid;
    private final long loadedAt = System.currentTimeMillis();
    private volatile boolean preloaded;
    private volatile Player player;
    private volatile String teamID;
    private volatile TeamCacheObject teamCacheObject = null;

    private volatile boolean jail = false;
    private volatile long jailEnd = 0;
    private volatile boolean isWanted = false;
    private volatile @Nullable Integer jailCell;
    private volatile boolean caught;
    // when the running warrant started (epoch millis), 0 without one; mirrors PlayerRepository's wanted_since
    private volatile long wantedSince;


    private PlayerCacheObject(@NotNull PlayerData data) {
        this.uuid = data.uuid();
        this.teamID = data.teamID();
        if (teamID != null) {
            this.teamCacheObject = TeamCache.getTeam(teamID);
        }
        this.jail = data.jail();
        this.jailEnd = data.jailEnd();
        this.isWanted = data.isWanted();
        this.jailCell = data.jailCell();
        this.caught = data.caught();
        this.wantedSince = data.wantedSince();
    }

    /**
     * For a bot (PvpBot fake player): the defaults of a new player without touching the database, so no listener or
     * task creates a player row for it. Writes through it hit no row.
     */
    static PlayerCacheObject detached(@NotNull UUID uuid) {
        return new PlayerCacheObject(new PlayerData(uuid, 0, null, false, 0, false, null, false, 0));
    }

    // Loads the player from the database (blocking). A missing player is created like on the first login.
    static PlayerCacheObject load(@NotNull UUID uuid) {
        PlayerData data = PlayerRepository.getPlayer(uuid);
        if (data == null) {
            PlayerRepository.createIfAbsent(uuid);
            data = PlayerRepository.getPlayer(uuid);
            if (data == null) throw new IllegalStateException("no player row for " + uuid);
        }
        return new PlayerCacheObject(data);
    }

    void attach(@NotNull Player player) {
        if (this.player != player) this.player = player;
    }

    // the session this entry belongs to, null for a preload whose join has not happened yet
    @Nullable Player attached() {
        return player;
    }

    long loadedAt() {
        return loadedAt;
    }

    // loaded at the pre-login (not by a cache miss during a session)
    void markPreloaded() {
        preloaded = true;
    }

    boolean preloaded() {
        return preloaded;
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


    public void changeTeamID(String id) {
        this.teamID = id;
        if (id != null) {
            this.teamCacheObject = TeamCache.getTeam(id);
        }else {
            this.teamCacheObject = null;
        }
        PlayerRepository.changeTeamID(uuid, id);
    }


    // the database has the change already (a transaction wrote it): only the cached fields
    void teamChanged(String id) {
        this.teamID = id;
        this.teamCacheObject = id == null ? null : TeamCache.getTeam(id);
    }


    // re-reads teamID from the database and sets the names on the main thread
    public void updatePlayer() {
        Tasks.async(() -> {
            // same lock as CacheHandler#addPlayerToTeam, a join during the read is not overwritten with the old value
            synchronized (this) {
                PlayerData data = PlayerRepository.getPlayer(uuid);
                if (data == null) return;
                this.teamID = data.teamID();
                this.teamCacheObject = teamID == null ? null : TeamCache.getTeam(teamID);
            }

            Tasks.sync(() -> {
                Player player = getPlayer();
                if (player == null || !player.isOnline()) return;
                // keeps the jail/wanted prefix, the death listener recognizes the bounty by it
                JailHandler.refreshPlayerName(player, this);
            });
        });
    }




    // jail for the given hours in the cell (null: none set up yet); caught: a bounty hunter got them, no escape
    public void setJail(int hours, @Nullable Integer cell, boolean caught) {
        this.jail = true;
        long now = System.currentTimeMillis();
        long futureMillis = now + (hours * 60L * 60L * 1000L);
        this.jailEnd = futureMillis;
        this.jailCell = cell;
        this.caught = caught;
        PlayerRepository.setJail(uuid, futureMillis, cell, caught);
    }
    public void setJailCell(@Nullable Integer cell) {
        this.jailCell = cell;
        PlayerRepository.setJailCell(uuid, cell);
    }
    public void unsetJail(boolean isEscaped) {
        this.jail = false;
        this.jailCell = null;
        this.caught = false;
        if (isEscaped) {
            long now = System.currentTimeMillis();
            long futureMillis = now + (72L * 60L * 60L * 1000L);
            this.jailEnd = futureMillis;
        }else {
            this.jailEnd = 0L;
        }
        this.isWanted = isEscaped;
        // an escape starts a new warrant
        this.wantedSince = isEscaped ? System.currentTimeMillis() : 0;
        PlayerRepository.unsetJail(uuid, this.jailEnd, this.isWanted);
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
    public @Nullable Integer getJailCell() {
        return jailCell;
    }
    public boolean isCaught() {
        return caught;
    }
    // wanted for a limited time (black market), ends like an escape
    public void setWantedUntil(long wantedEnd) {
        warrantStartsOrGoesOn();
        this.isWanted = true;
        this.jailEnd = wantedEnd;
        PlayerRepository.setWantedUntil(uuid, wantedEnd);
    }

    public void setWanted(boolean wanted) {
        if (wanted) {
            warrantStartsOrGoesOn();
        } else {
            wantedSince = 0;
        }
        isWanted = wanted;
        PlayerRepository.setIsWanted(uuid, wanted);
    }

    // before a warrant is written: a running one keeps its start (like PlayerRepository.WANTED_SINCE_ON_WARRANT)
    private void warrantStartsOrGoesOn() {
        long now = System.currentTimeMillis();
        boolean running = isWanted && !jail && (jailEnd == 0 || jailEnd > now);
        if (!running || wantedSince == 0) wantedSince = now;
    }

    /** When the running warrant started (epoch millis), 0 without one. */
    public long getWantedSince() {
        return wantedSince;
    }

    /** Test mode (admins): the running warrant counts as started at that time. Blocking. */
    public void backdateWanted(long since) {
        if (!isWanted) return;
        wantedSince = since;
        PlayerRepository.setWantedSince(uuid, since);
    }

    /** Wanted right now: a warrant that has not run out, not in jail. */
    public boolean isWantedNow() {
        long end = jailEnd;
        return isWanted && !jail && (end == 0 || end > System.currentTimeMillis());
    }



    // Getter
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
