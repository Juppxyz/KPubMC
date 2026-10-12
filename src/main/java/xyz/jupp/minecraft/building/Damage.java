package xyz.jupp.minecraft.building;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A player's damage to state property (a paid build): a flat fee for the incident plus a sum per broken block,
 * due within a few days at the Bauamt, otherwise the player is wanted. One open incident per player and build.
 */
public final class Damage {

    private volatile long id;
    private final long buildId;
    private final UUID player;
    private final String playerName;
    private volatile int blocks;
    private volatile int amount;
    private final Instant createdAt;
    private final Instant dueAt;
    private volatile boolean wanted;
    private volatile long noticedAt;

    Damage(long id, long buildId, @NotNull UUID player, @NotNull String playerName, int blocks, int amount,
           @NotNull Instant createdAt, @NotNull Instant dueAt, boolean wanted) {
        this.id = id;
        this.buildId = buildId;
        this.player = player;
        this.playerName = playerName;
        this.blocks = blocks;
        this.amount = amount;
        this.createdAt = createdAt;
        this.dueAt = dueAt;
        this.wanted = wanted;
    }

    public long id() {
        return id;
    }

    void setId(long id) {
        this.id = id;
    }

    public long buildId() {
        return buildId;
    }

    public @NotNull UUID player() {
        return player;
    }

    public @NotNull String playerName() {
        return playerName;
    }

    public int blocks() {
        return blocks;
    }

    public int amount() {
        return amount;
    }

    public @NotNull Instant createdAt() {
        return createdAt;
    }

    public @NotNull Instant dueAt() {
        return dueAt;
    }

    public boolean wanted() {
        return wanted;
    }

    void markWanted() {
        wanted = true;
    }

    void addBlock() {
        blocks++;
        amount += Bauamt.DAMAGE_PER_BLOCK;
    }

    /** Breaking a row of blocks gives one notice every few seconds, not one per block. */
    boolean shouldNotice(long cooldownMillis) {
        long now = System.currentTimeMillis();
        if (now - noticedAt < cooldownMillis) return false;
        noticedAt = now;
        return true;
    }

}
