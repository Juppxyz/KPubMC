package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import xyz.jupp.minecraft.Main;

public class HetznerHandler {
    private HetznerHandler() {}

    private boolean isSaving = false;
    private boolean emptyPlayerCheck = false;

    private static final HetznerHandler instance = new HetznerHandler();
    public static HetznerHandler getInstance() {
        return instance;
    }


}
