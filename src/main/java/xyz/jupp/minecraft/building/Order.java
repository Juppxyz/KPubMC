package xyz.jupp.minecraft.building;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/** An order a player took at the Bauamt: the permission to get paid for one copy of a template. */
public final class Order {

    private final long id;
    private final UUID player;
    private final int templateId;
    private final Instant takenAt;

    Order(long id, @NotNull UUID player, int templateId, @NotNull Instant takenAt) {
        this.id = id;
        this.player = player;
        this.templateId = templateId;
        this.takenAt = takenAt;
    }

    public long id() {
        return id;
    }

    public @NotNull UUID player() {
        return player;
    }

    public int templateId() {
        return templateId;
    }

    public @NotNull Instant takenAt() {
        return takenAt;
    }

}
