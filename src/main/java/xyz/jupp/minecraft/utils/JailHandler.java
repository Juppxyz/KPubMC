package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.economy.Loans;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Jail and wanted system. The public methods may be called from any thread: cache and database writes run
 * off the main thread, teleports, names and messages on it.
 */
public final class JailHandler {

    private static volatile JailArea jailArea;

    private static final Set<UUID> wantedPlayers = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> alreadyKilledPlayer = ConcurrentHashMap.newKeySet();
    public static Set<UUID> getAlreadyKilledPlayer() {
        return alreadyKilledPlayer;
    }

    // players whose jail state is being written right now: no escape check and no second release meanwhile
    private static final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    // cache and database receive the jail changes in the same order
    private static final Object LOCK = new Object();


    private JailHandler() {
        // Utility-Klasse
    }

    public static void initJails(@NotNull Location corner1, @NotNull Location corner2) {
        jailArea = new JailArea(corner1.getWorld(),
                Math.min(corner1.getX(), corner2.getX()), Math.max(corner1.getX(), corner2.getX()),
                Math.min(corner1.getY(), corner2.getY()), Math.max(corner1.getY(), corner2.getY()),
                Math.min(corner1.getZ(), corner2.getZ()), Math.max(corner1.getZ(), corner2.getZ()));
        Logger.console("JailHandler initialisiert: " + locToString(corner1) + " <-> " + locToString(corner2));
    }

    private static String locToString(Location loc) {
        if (loc == null || loc.getWorld() == null) return "null";
        return loc.getWorld().getName() + " (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }


    public static boolean isPlayerWanted(@NotNull Player player) {
        return wantedPlayers.contains(player.getUniqueId());
    }

    public static boolean isPlayerInJail(@NotNull Player player) {
        return cache(player).isJail();
    }


    public static void setPlayerWanted(@NotNull Player player, boolean wanted) {
        PlayerCacheObject pco = cache(player);

        if (wanted) {
            wantedPlayers.add(player.getUniqueId());
        } else {
            wantedPlayers.remove(player.getUniqueId());
        }

        offMainThread(() -> {
            synchronized (LOCK) {
                pco.setWanted(wanted);
            }
            refreshPlayerName(player, pco);
        });
    }


    /** Wanted for the given hours (e.g. caught at the black market), with the usual broadcast. */
    public static void markWanted(@NotNull Player player, int hours, @NotNull String reason) {
        PlayerCacheObject pco = cache(player);
        wantedPlayers.add(player.getUniqueId());
        long wantedEnd = System.currentTimeMillis() + hours * 60L * 60L * 1000L;
        offMainThread(() -> {
            synchronized (LOCK) {
                // "until caught" (jail_end 0, e.g. an unpaid loan) and a longer wanted (after an escape) stay as they are
                boolean keep = pco.isJail() || (pco.isWanted() && (pco.getJailEnd() == 0 || pco.getJailEnd() >= wantedEnd));
                if (!keep) pco.setWantedUntil(wantedEnd);
            }
            refreshPlayerName(player, pco);
        });
        playerWantedBroadcast(player, reason);
    }


    /** Wanted until a player catches them (no end), e.g. for an unpaid loan; with the usual broadcast. */
    public static void markWantedUntilCaught(@NotNull Player player, @NotNull String reason) {
        PlayerCacheObject pco = cache(player);
        wantedPlayers.add(player.getUniqueId());
        offMainThread(() -> {
            synchronized (LOCK) {
                pco.setWantedUntil(0);
            }
            refreshPlayerName(player, pco);
        });
        playerWantedBroadcast(player, reason);
    }

    /** The wanted broadcast for a player who is offline (their status is already in the database). */
    public static void broadcastWanted(@NotNull String name, @NotNull String reason) {
        String msg = Main.getChatPrefix() + "§4§lGESUCHT §c" + name + " §7(" + reason + "§7)";
        onMainThread(() -> Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg)));
        Logger.console("Wanted-Broadcast: " + name + " - " + reason);
    }

    // team name plus the jail/wanted prefix, every name refresh has to go through here or the prefix is lost
    public static void refreshPlayerName(@NotNull Player player, @NotNull PlayerCacheObject pco) {
        onMainThread(() -> {
            String finalName = teamFormattedName(player, pco);
            if (pco.isJail()) {
                finalName = Main.getInJailPrefix() + finalName;
            } else if (pco.isWanted()) {
                finalName = Main.getIsWantedPrefix() + finalName;
            }

            player.playerListName(Text.listName(finalName, player.getName()));
            player.setDisplayName(finalName);
        });
    }

    private static String teamFormattedName(@NotNull Player player, @NotNull PlayerCacheObject pco) {
        String baseName = player.getName();
        TeamCacheObject team = pco.getTeamCacheObject();
        if (pco.getTeamID() == null || team == null) {
            return pco.getTeamColor() + baseName;
        }

        String uuid = player.getUniqueId().toString();
        if (team.getTeamOwner().equals(uuid)) {
            return team.getTeamColor() + "§l" + baseName;
        }
        if (team.getTeamVices().contains(uuid)) {
            return team.getTeamColor() + "§o" + baseName;
        }
        return team.getTeamColor() + baseName;
    }



    public static void handleJoin(@NotNull Player player) {
        PlayerCacheObject pco = cache(player);
        boolean loanDefault = false;
        if (pco.isWanted() && pco.getJailEnd() == 0) {
            try {
                Loans.Loan loan = Loans.current(player.getUniqueId());
                loanDefault = loan != null && loan.state() == Loans.State.DEFAULTED;
            } catch (RuntimeException ignored) {
                // only picks the message
            }
        }
        boolean defaulted = loanDefault;

        onMainThread(() -> {
            if (!pco.isWanted() && pco.isJail() && !isInJailArea(player.getLocation())) {
                player.sendMessage(Main.getChatPrefix() + "Du wurdest verhaftet und deswegen ins Gefägnis teleportiert.");
                player.teleport(Locations.getJailSpawn());
                return;
            }

            if (pco.isWanted()) {
                long jailEnd = pco.getJailEnd();
                if (jailEnd > 0 && System.currentTimeMillis() >= jailEnd && !pco.isJail()) {
                    // ran out while offline: ends like in the watcher
                    wantedPlayers.remove(player.getUniqueId());
                    offMainThread(() -> {
                        synchronized (LOCK) {
                            pco.unsetJail(false);
                        }
                        refreshPlayerName(player, pco);
                    });
                    player.sendMessage(Main.getChatPrefix() + "§aDie Fahndung nach dir wurde eingestellt.");
                } else if (jailEnd > 0 && System.currentTimeMillis() >= jailEnd) {
                    releasePlayer(player, pco);
                } else if (defaulted) {
                    player.sendMessage(Main.getChatPrefix() + "§cDein Kredit bei Basil ist geplatzt, er hat sich genommen, was auf dem Konto war.");
                    player.sendMessage(Main.getChatPrefix() + "§cDu wirst gesucht, bis dich jemand fasst. §7Neue Kredite erst nach der Strafe und einer Entschuldigung.");
                    refreshPlayerName(player, pco);
                    wantedPlayers.add(player.getUniqueId());
                } else {
                    player.sendMessage(Main.getChatPrefix() + "§cDu bist weiterhin auf der Flucht und Gesucht!");
                    refreshPlayerName(player, pco);
                    wantedPlayers.add(player.getUniqueId());
                }
            } else {
                refreshPlayerName(player, pco);
            }
        });
    }


    /**
     * Broadcast, dass ein Spieler wanted ist – z.B. nach Flucht.
     */
    public static void playerWantedBroadcast(@NotNull Player wantedPlayer, @NotNull String reason) {
        String msg = Main.getChatPrefix()
                + "§4§lGESUCHT §c" + wantedPlayer.getName()
                + " §7(" + reason + "§7)";
        onMainThread(() -> Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg)));
        Logger.console("Wanted-Broadcast: " + wantedPlayer.getName() + " - " + reason);
    }


    public static void jailPlayer(@NotNull Player player, int hours, @NotNull String reason) {
        PlayerCacheObject pco = cache(player);
        pendingPlayers.add(player.getUniqueId());
        wantedPlayers.remove(player.getUniqueId());

        changeJailState(player, () -> {
            pco.setJail(true, hours);
            pco.setWanted(false);
            return true;
        }, () -> {
            player.teleport(Locations.getJailSpawn());
            player.setRespawnLocation(Locations.getJailSpawn());
            player.sendMessage(Main.getChatPrefix()
                    + "§cDu wurdest für §e" + hours + "§c Stunde(n) inhaftiert. Grund: §f" + reason);
            refreshPlayerName(player, pco);
        });

        Logger.console("Jail: " + player.getName() + " für " + hours + "h. Grund: " + reason);
    }



    public static void releasePlayer(@NotNull Player player) {
        releasePlayer(player, cache(player));
    }

    private static void releasePlayer(@NotNull Player player, @NotNull PlayerCacheObject pco) {
        if (!pco.isJail() || !pendingPlayers.add(player.getUniqueId())) return;

        changeJailState(player, () -> {
            if (!pco.isJail()) return false;
            pco.unsetJail(false);
            return true;
        }, () -> {
            player.teleport(Locations.getCurrentSpawn());
            player.sendMessage(Main.getChatPrefix() + "§aDu bist wieder frei. Verhalte dich ab jetzt besser.");
            Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§fDer Spieler §2" + player.getName() + " §fwurde aus dem Gefängnis §aentlassen§f."));
            alreadyKilledPlayer.remove(player.getUniqueId());
            refreshPlayerName(player, pco);
        });
    }


    // PlayerMoveEvent (main thread): the answer decides whether the move is cancelled, the escape itself is written async
    public static boolean handlePossibleEscape(@NotNull Player player) {
        PlayerCacheObject pco = cache(player);
        if (!pco.isJail()) return false;

        UUID uuid = player.getUniqueId();
        if (pendingPlayers.contains(uuid)) return false;

        if (isInJailArea(player.getLocation())) {
            return alreadyKilledPlayer.contains(uuid);
        }

        if (!pendingPlayers.add(uuid)) return false;
        changeJailState(player, () -> {
            if (!pco.isJail()) return false;
            pco.unsetJail(true);
            pco.setWanted(true);
            wantedPlayers.add(uuid);
            return true;
        }, () -> {
            playerWantedBroadcast(player, "Gefängnisflucht");
            player.sendMessage(Main.getChatPrefix() + "§cDu bist aus dem Gefängnis geflohen! Du bist nun §4§lGESUCHT§c.");
            Bukkit.broadcast(Text.section(Main.getChatPrefix() + "Der Spieler §4" + player.getName() + " §fist aus dem Gefängnis §cgeflohen §fund offiziell Vogelfrei."));
            Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§fMehr Infos gibts mit§8: §a/wanted"));
            refreshPlayerName(player, pco);
        });
        return false;
    }


    public static void startJailWatcherTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), () -> {
            long now = System.currentTimeMillis();

            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerCacheObject pco = cache(player);

                // Wanted-Set in Sync mit Cache halten
                if (pco.isWanted()) {
                    wantedPlayers.add(player.getUniqueId());
                } else {
                    wantedPlayers.remove(player.getUniqueId());
                }

                // a wanted status ends when its time is over
                if (pco.isWanted() && !pco.isJail() && pco.getJailEnd() > 0 && now >= pco.getJailEnd()) {
                    synchronized (LOCK) {
                        pco.unsetJail(false);
                    }
                    wantedPlayers.remove(player.getUniqueId());
                    refreshPlayerName(player, pco);
                    onMainThread(() -> player.sendMessage(Main.getChatPrefix() + "§aDie Fahndung nach dir wurde eingestellt."));
                    continue;
                }

                if (pco.isJail()) {
                    long jailEnd = pco.getJailEnd();
                    if (jailEnd > 0 && now >= jailEnd) {
                        releasePlayer(player, pco);
                    }
                }
            }

        }, 20L, 20L * 30);
    }


    public static boolean isInJailArea(Location loc) {
        JailArea area = jailArea;
        return loc != null && area != null && area.contains(loc);
    }


    // Writes the new state off the main thread (under LOCK), then runs the Bukkit part on the main thread.
    // The caller has marked the player as pending, the mark is removed when everything is done.
    private static void changeJailState(@NotNull Player player, @NotNull BooleanSupplier stateChange, @NotNull Runnable bukkitPart) {
        UUID uuid = player.getUniqueId();
        offMainThread(() -> {
            boolean changed = false;
            try {
                synchronized (LOCK) {
                    changed = stateChange.getAsBoolean();
                }
            } finally {
                if (!changed) pendingPlayers.remove(uuid);
            }
            if (!changed) return;

            Tasks.sync(() -> {
                try {
                    bukkitPart.run();
                } finally {
                    pendingPlayers.remove(uuid);
                }
            });
        });
    }

    private static void onMainThread(@NotNull Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Tasks.sync(task);
        }
    }

    // cache and database writes block, so they never run on the main thread
    private static void offMainThread(@NotNull Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            Tasks.async(task);
        } else {
            task.run();
        }
    }

    private static PlayerCacheObject cache(@NotNull Player player) {
        return CacheHandler.getInstance().getPlayerInCache(player);
    }


    private record JailArea(World world, double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {

        boolean contains(@NotNull Location loc) {
            if (world == null || !world.equals(loc.getWorld())) return false;

            double x = loc.getX();
            double y = loc.getY();
            double z = loc.getZ();

            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }
}
