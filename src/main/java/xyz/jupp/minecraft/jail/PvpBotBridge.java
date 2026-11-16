package xyz.jupp.minecraft.jail;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The few calls into the PvpBot plugin the deputies need (Deputies). PvpBot has no API artifact, so it is reached by
 * reflection: nothing to compile against, and if PvpBot is missing or its classes change, the deputies are simply off
 * (one warning in the log) while the rest of KPubMC runs on. Checked against PvpBot 2.0.0 and 3.0.1. Main thread only.
 */
final class PvpBotBridge {

    private PvpBotBridge() {}

    private static @Nullable Plugin resolvedFor;
    private static boolean failed;
    private static Method pluginGet, bots, spawn, markTransient, remove, contains, kits, kitExists, factions, factionExists;
    private static Method setForcedTarget, guardSet, guardClear;

    /** PvpBot is there and every call the deputies need was found. */
    static boolean available() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PvpBot");
        if (plugin == null || !plugin.isEnabled()) return false;
        if (plugin == resolvedFor) return !failed;
        // first call, or PvpBot was reloaded: resolve the methods again
        resolvedFor = plugin;
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> main = Class.forName("com.stashes.pvpbot.PvpBotPlugin", true, loader);
            Class<?> manager = Class.forName("com.stashes.pvpbot.bot.BotManager", true, loader);
            Class<?> kitStore = Class.forName("com.stashes.pvpbot.bot.BotKits", true, loader);
            Class<?> factionStore = Class.forName("com.stashes.pvpbot.bot.BotFaction", true, loader);
            Class<?> combat = Class.forName("com.stashes.pvpbot.bot.BotCombat", true, loader);
            Class<?> guard = Class.forName("com.stashes.pvpbot.bot.BotGuard", true, loader);
            pluginGet = main.getMethod("get");
            bots = main.getMethod("bots");
            kits = main.getMethod("kits");
            factions = main.getMethod("factions");
            spawn = manager.getMethod("spawn", String.class, Location.class);
            markTransient = manager.getMethod("markTransient", String.class);
            remove = manager.getMethod("remove", String.class);
            contains = manager.getMethod("contains", String.class);
            kitExists = kitStore.getMethod("exists", String.class);
            factionExists = factionStore.getMethod("exists", String.class);
            setForcedTarget = combat.getMethod("setForcedTarget", String.class, String.class);
            guardSet = guard.getMethod("set", String.class, Location.class, double.class, double.class);
            guardClear = guard.getMethod("clear", String.class);
            failed = false;
            Main.getInstance().getSLF4JLogger().info("PvpBot {} found, the sheriff's deputies are on duty", plugin.getPluginMeta().getVersion());
        } catch (ReflectiveOperationException | LinkageError e) {
            failed = true;
            Main.getInstance().getSLF4JLogger().warn("PvpBot is installed but does not fit (no deputies): {}", e.toString());
        }
        return !failed;
    }

    // a call that fails switches the deputies off until PvpBot is reloaded, with one warning
    private static @Nullable Object call(@NotNull Method method, @Nullable Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Main.getInstance().getSLF4JLogger().warn("PvpBot {} failed: {}", method.getName(), String.valueOf(e.getCause()));
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            Main.getInstance().getSLF4JLogger().warn("PvpBot {} cannot be called (no deputies): {}", method.getName(), e.toString());
            return null;
        }
    }

    private static @Nullable Object store(@NotNull Method getter) {
        Object plugin = call(pluginGet, null);
        return plugin == null ? null : call(getter, plugin);
    }

    /** A bot at the location; null if the name is taken or the spawn failed. Not saved: it never comes back after a restart. */
    static @Nullable Player spawn(@NotNull String name, @NotNull Location at) {
        Object manager = store(bots);
        if (manager == null) return null;
        Object bot = call(spawn, manager, name, at);
        if (!(bot instanceof Player player)) return null;
        call(markTransient, manager, name);
        return player;
    }

    static void remove(@NotNull String name) {
        if (!available()) return;
        Object manager = store(bots);
        if (manager != null) call(remove, manager, name);
        call(guardClear, null, name);
    }

    static boolean isBot(@NotNull String name) {
        Object manager = store(bots);
        return manager != null && Boolean.TRUE.equals(call(contains, manager, name));
    }

    static boolean kitExists(@NotNull String kit) {
        Object store = store(kits);
        return store != null && Boolean.TRUE.equals(call(kitExists, store, kit));
    }

    static boolean factionExists(@NotNull String faction) {
        Object store = store(factions);
        return store != null && Boolean.TRUE.equals(call(factionExists, store, faction));
    }

    /** The bot goes after this player (PvpBot's /pvpbot attack); only within the faction's max-target-distance and the guard post. */
    static void attack(@NotNull String bot, @NotNull String target) {
        call(setForcedTarget, null, bot, target);
    }

    /** The bot stays within radius of the anchor and drops a target that is further than radius + 8 from it (PvpBot's guard post). */
    static void guard(@NotNull String bot, @NotNull Location anchor, double radius) {
        call(guardSet, null, bot, anchor, radius, 0.0);
    }

    /** A /pvpbot command from the console, for what PvpBot keeps in its own files (factions, kits). */
    static void command(@NotNull String arguments) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pvpbot " + arguments);
    }
}
