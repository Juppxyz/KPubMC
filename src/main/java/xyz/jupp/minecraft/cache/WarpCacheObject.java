package xyz.jupp.minecraft.cache;

import org.jetbrains.annotations.NotNull;

public class WarpCacheObject {

    private final double x;
    private final double y;
    private final double z;
    private final String worldName;

    WarpCacheObject(double x, double y, double z, @NotNull String worldName) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.worldName = worldName;
    }

    public double getX() {return x;}

    public double getY() {return y;}

    public double getZ() {return z;}

    public String getWorldName() {return worldName;}
}
