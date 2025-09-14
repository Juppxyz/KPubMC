package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import xyz.jupp.minecraft.Main;

public class HetznerHandler {
    private HetznerHandler() {}

    private boolean isCheckActive = false;

    private static final HetznerHandler instance = new HetznerHandler();
    public static HetznerHandler getInstance() {
        return instance;
    }

    public void runCheck() {
        if (!isCheckActive) return;
        Logger.console("start no activity check..");
        isCheckActive = true;

        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            int currentOnlinePlayers = Bukkit.getOnlinePlayers().size();
            if (currentOnlinePlayers == 0) {
                Bukkit.getServer().shutdown();
                return;
            }
        }, 20*60*60L);
    }

}
