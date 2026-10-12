package xyz.jupp.minecraft.building;

import org.bukkit.block.structure.StructureRotation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A copy of a template somewhere in the world: reported by the plugin when its last block was placed (PENDING), paid
 * at the Bauamt (INTACT), found broken by the inspection (DAMAGED) and written off a week later (GONE, the place is
 * free again). The admin's own model is stored as an INTACT build with no reward, so nobody can claim it.
 */
public final class Build {

    public enum State { PENDING, INTACT, DAMAGED, GONE }

    private volatile long id;
    private final int templateId;
    private final UUID player;
    private final String playerName;
    private final String world;
    private final int x, y, z;
    private final int sizeX, sizeY, sizeZ;
    private final StructureRotation rotation;
    private final boolean mirrored;
    private volatile State state;
    private volatile int reward;
    private final Instant createdAt;
    private volatile @Nullable Instant paidAt;
    private volatile @Nullable Instant damagedAt;

    Build(long id, int templateId, @NotNull UUID player, @NotNull String playerName, @NotNull String world, int x, int y, int z,
          int sizeX, int sizeY, int sizeZ, @NotNull StructureRotation rotation, boolean mirrored, @NotNull State state, int reward,
          @NotNull Instant createdAt, @Nullable Instant paidAt, @Nullable Instant damagedAt) {
        this.id = id;
        this.templateId = templateId;
        this.player = player;
        this.playerName = playerName;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.rotation = rotation;
        this.mirrored = mirrored;
        this.state = state;
        this.reward = reward;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
        this.damagedAt = damagedAt;
    }

    public long id() {
        return id;
    }

    void setId(long id) {
        this.id = id;
    }

    public int templateId() {
        return templateId;
    }

    public @NotNull UUID player() {
        return player;
    }

    public @NotNull String playerName() {
        return playerName;
    }

    public @NotNull String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    /** Block coordinates of the middle, for messages. */
    public int centerX() {
        return x + sizeX / 2;
    }

    public int centerZ() {
        return z + sizeZ / 2;
    }

    public @NotNull StructureRotation rotation() {
        return rotation;
    }

    public boolean mirrored() {
        return mirrored;
    }

    public @NotNull State state() {
        return state;
    }

    public int reward() {
        return reward;
    }

    public @NotNull Instant createdAt() {
        return createdAt;
    }

    public @Nullable Instant paidAt() {
        return paidAt;
    }

    public @Nullable Instant damagedAt() {
        return damagedAt;
    }

    void paid(int reward) {
        this.reward = reward;
        this.paidAt = Instant.now();
        this.state = State.INTACT;
    }

    void damaged() {
        this.damagedAt = Instant.now();
        this.state = State.DAMAGED;
    }

    void repaired() {
        this.damagedAt = null;
        this.state = State.INTACT;
    }

    void gone() {
        this.state = State.GONE;
    }

    boolean overlaps(@NotNull String world, int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        return this.world.equals(world)
                && this.x < x + sizeX && x < this.x + this.sizeX
                && this.y < y + sizeY && y < this.y + this.sizeY
                && this.z < z + sizeZ && z < this.z + this.sizeZ;
    }

    boolean samePlace(int templateId, @NotNull String world, int x, int y, int z, @NotNull StructureRotation rotation, boolean mirrored) {
        return this.templateId == templateId && this.world.equals(world) && this.x == x && this.y == y && this.z == z
                && this.rotation == rotation && this.mirrored == mirrored;
    }

}
