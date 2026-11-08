package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.economy.Loans;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.jail.JailCells;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Jail and wanted system. The public methods may be called from any thread: cache and database writes run
 * off the main thread, teleports, names and messages on it. Prisoners sit in the cells of JailCells.
 */
public final class JailHandler {

    // a wanted player who dies goes to jail for this long
    public static final int DEATH_SENTENCE_HOURS = 72;

    private static final Set<UUID> wantedPlayers = ConcurrentHashMap.newKeySet();

    // players whose jail state is being written right now: no escape check and no second release meanwhile
    private static final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    // cache and database receive the jail changes in the same order
    private static final Object LOCK = new Object();


    private JailHandler() {
        // Utility-Klasse
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
        // a player alone is nobody's boss: just the title's colour
        if (team.getTeamOwner().equals(uuid) && !team.isSolo()) {
            return team.getTeamColor() + "§l" + baseName;
        }
        if (team.getTeamVices().contains(uuid)) {
            return team.getTeamColor() + "§o" + baseName;
        }
        return team.getTeamColor() + baseName;
    }



    /** Where the player is put in jail: their cell's spawn (any ready cell's without one), null if no cell is set up. */
    public static @Nullable Location cellSpawn(@NotNull PlayerCacheObject pco) {
        return JailCells.spawnFor(pco.getJailCell());
    }

    public static void handleJoin(@NotNull Player player) {
        PlayerCacheObject pco = cache(player);
        // a prisoner whose cell is gone (or who was jailed before there was one) gets one now
        JailCells.Cell ownCell = JailCells.get(pco.getJailCell());
        if (pco.isJail() && (ownCell == null || !ownCell.ready())) {
            Integer cell = JailCells.pickCell();
            if (cell != null) {
                synchronized (LOCK) {
                    pco.setJailCell(cell);
                }
            }
        }
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
            Location cell = cellSpawn(pco);
            if (!pco.isWanted() && pco.isJail() && cell != null && !JailCells.contains(player.getLocation())) {
                player.sendMessage(Main.getChatPrefix() + "Du wurdest verhaftet und deswegen ins Gefängnis teleportiert.");
                player.teleport(cell);
                refreshPlayerName(player, pco);
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
            pco.setJail(hours, JailCells.pickCell(), false);
            pco.setWanted(false);
            return true;
        }, () -> {
            // the respawn point stays the player's own: a death in jail respawns in the cell anyway (DeathListener)
            Location cell = cellSpawn(pco);
            if (cell != null) player.teleport(cell);
            player.sendMessage(Main.getChatPrefix()
                    + "§cDu wurdest für §e" + hours + "§c Stunde(n) inhaftiert. Grund: §f" + reason);
            refreshPlayerName(player, pco);
        });

        Logger.console("Jail: " + player.getName() + " für " + hours + "h. Grund: " + reason);
    }

    /**
     * A wanted player died: DEATH_SENTENCE_HOURS in jail, they respawn in the cell (DeathListener). caught: a bounty
     * hunter got them, they cannot escape this sentence.
     */
    public static void jailAfterDeath(@NotNull Player player, boolean caught) {
        PlayerCacheObject pco = cache(player);
        wantedPlayers.remove(player.getUniqueId());
        offMainThread(() -> {
            synchronized (LOCK) {
                pco.setWanted(false);
                pco.setJail(DEATH_SENTENCE_HOURS, JailCells.pickCell(), caught);
            }
            refreshPlayerName(player, pco);
        });
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
            refreshPlayerName(player, pco);
        });
    }


    // PlayerMoveEvent (main thread): a prisoner outside every cell has escaped (written async); one a bounty hunter
    // caught goes back into the cell instead
    public static void handlePossibleEscape(@NotNull Player player) {
        PlayerCacheObject pco = cache(player);
        if (!pco.isJail()) return;

        UUID uuid = player.getUniqueId();
        if (pendingPlayers.contains(uuid)) return;
        // without a ready cell there is no jail to escape from
        if (!JailCells.anyReady() || JailCells.contains(player.getLocation())) return;

        // jailed before there was a cell: put into one, that is no escape
        if (pco.getJailCell() == null) {
            if (!pendingPlayers.add(uuid)) return;
            changeJailState(player, () -> {
                Integer cell = JailCells.pickCell();
                if (cell == null || !pco.isJail()) return false;
                pco.setJailCell(cell);
                return true;
            }, () -> {
                Location spawn = cellSpawn(pco);
                if (spawn != null) player.teleport(spawn);
            });
            return;
        }

        if (pco.isCaught()) {
            Location cell = cellSpawn(pco);
            if (cell == null) return;
            player.teleport(cell);
            player.sendMessage(Main.getChatPrefix() + "§cDich hat ein Kopfgeldjäger gefasst, diese Strafe sitzt du ab.");
            return;
        }

        if (!pendingPlayers.add(uuid)) return;
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

}
