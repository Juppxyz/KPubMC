package xyz.jupp.minecraft.utils;

import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

public final class Logger {

    private Logger() {
    }

    // Info line through the plugin logger, which adds the [KPubMC] prefix itself.
    public static void console(@NotNull String message) {
        Main.getInstance().getSLF4JLogger().info(message);
    }

}
