package xyz.jupp.minecraft.cache;

import org.jetbrains.annotations.NotNull;

public class WarpCacheObject {

    private double x = 0.0D;
    private double y = 0.0D;
    private double z = 0.0D;
    private String worldName = null;

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
