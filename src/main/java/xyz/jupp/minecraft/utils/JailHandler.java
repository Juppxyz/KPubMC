package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


public final class JailHandler {

    private static Location jailCorner1;
    private static Location jailCorner2;

    private static final Set<UUID> wantedPlayers = new HashSet<>();
    private static final Set<UUID> alreadyKilledPlayer = new HashSet<>();
    public static Set<UUID> getAlreadyKilledPlayer() {
        return alreadyKilledPlayer;
    }


    private JailHandler() {
        // Utility-Klasse
    }

    public static void initJails(@NotNull Location corner1, @NotNull Location corner2) {
        jailCorner1 = corner1;
        jailCorner2 = corner2;
        Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix()
                + "JailHandler initialisiert: "
                + locToString(corner1) + " <-> " + locToString(corner2));
    }

    private static String locToString(Location loc) {
        if (loc == null || loc.getWorld() == null) return "null";
        return loc.getWorld().getName() + " (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }


    public static boolean isPlayerWanted(@NotNull Player player) {
        return wantedPlayers.contains(player.getUniqueId());
    }

    public static boolean isPlayerInJail(@NotNull Player player) {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        return pco != null && pco.isJail();
    }


    public static void setPlayerWanted(@NotNull Player player, boolean wanted) {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        if (pco == null) return;

        pco.setWanted(wanted);

        if (wanted) {
            wantedPlayers.add(player.getUniqueId());
        } else {
            wantedPlayers.remove(player.getUniqueId());
        }

        refreshPlayerName(player);
    }



    public static void refreshPlayerName(@NotNull Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
            String baseName = player.getName();

            String color = pco.getTeamColor();
            String formattedName;

            if (pco.getTeamID() == null || pco.getTeamCacheObject() == null) {
                formattedName = color + baseName;
            } else {
                var team = pco.getTeamCacheObject();
                String uuid = player.getUniqueId().toString();

                if (team.getTeamOwner().equals(uuid)) {
                    formattedName = team.getTeamColor() + "§l" + baseName;
                } else if (team.getTeamVices().contains(uuid)) {
                    formattedName = team.getTeamColor() + "§o" + baseName;
                } else {
                    formattedName = team.getTeamColor() + baseName;
                }
            }

            String prefix = "";
            if (pco.isJail()) {
                prefix = "§c§lJ §8| ";
            } else if (pco.isWanted()) {
                prefix = "§c§lW §8| ";
            }

            String finalName = prefix + formattedName;

            player.setPlayerListName(finalName);
            player.setDisplayName(finalName);
        });
    }



    public static void handleJoin(@NotNull Player player) {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        if (pco == null) return;

        long now = System.currentTimeMillis();

        if (pco.isWanted()) {
            long jailEnd = pco.getJailEnd();
            if (jailEnd > 0 && now >= jailEnd) {
                releasePlayer(player);
            } else {
                // Noch in Haft – sicherstellen, dass er in der Jail-Region steht
                //Location cell = getRandomJailCell();
                //if (cell != null) {
                //    Bukkit.getScheduler().runTask(Main.getInstance(), () -> player.teleport(cell));
                //}
                player.sendMessage(Main.getChatPrefix() + "§cDu bist weiterhin auf der Flucht und Gesucht!");
                refreshPlayerName(player);
                wantedPlayers.add(player.getUniqueId());
            }
        } else {
            refreshPlayerName(player);
        }
    }


    /**
     * Broadcast, dass ein Spieler wanted ist – z.B. nach Flucht.
     */
    public static void playerWantedBroadcast(@NotNull Player wantedPlayer, @NotNull String reason) {
        String msg = Main.getChatPrefix()
                + "§4§lGESUCHT §c" + wantedPlayer.getName()
                + " §7(" + reason + "§7)";
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
        Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "Wanted-Broadcast: " + wantedPlayer.getName() + " - " + reason);
    }


    public static void jailPlayer(@NotNull Player player, int hours, @NotNull String reason) {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        if (pco == null) return;
        pco.setJail(true, hours);
        setPlayerWanted(player, false);

        Location cell = Locations.getJailSpawn();
        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
            player.teleport(cell);
            player.setRespawnLocation(Locations.getJailSpawn());
        }) ;

        player.sendMessage(Main.getChatPrefix()
                + "§cDu wurdest für §e" + hours + "§c Stunde(n) inhaftiert. Grund: §f" + reason);
        Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix()
                + "Jail: " + player.getName() + " für " + hours + "h. Grund: " + reason);

        refreshPlayerName(player);
    }



    public static void releasePlayer(@NotNull Player player) {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        if (pco == null) return;

        if (!pco.isJail()) return;

        pco.unsetJail(false);

        Location release = getSafeReleaseLocation(player);
        if (release != null) {
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> player.teleport(release));
        }

        player.sendMessage(Main.getChatPrefix() + "§aDu bist wieder frei. Verhalte dich ab jetzt besser.");
        Bukkit.getServer().broadcastMessage(Main.getChatPrefix() + "§fDer Spieler §2" + player.getName() + " §fwurde aus dem Gefängnis §aentlassen§f.");

        JailHandler.getAlreadyKilledPlayer().remove(player.getUniqueId());
        refreshPlayerName(player);
    }


    public static boolean handlePossibleEscape(@NotNull Player player) {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        if (pco == null) return false;
        if (!pco.isJail()) return false;

        Location loc = player.getLocation();
        if (JailHandler.getAlreadyKilledPlayer().contains(player.getUniqueId())
                && isInJailArea(loc)) {
            return true;
        }

        if (isInJailArea(loc)) return false;

        pco.unsetJail(true);
        setPlayerWanted(player, true);
        playerWantedBroadcast(player, "Gefängnisflucht");
        player.sendMessage(Main.getChatPrefix() + "§cDu bist aus dem Gefängnis geflohen! Du bist nun §4§lGESUCHT§c.");
        Bukkit.getServer().broadcastMessage(Main.getChatPrefix() + "Der Spieler §4" + player.getName() + " §fist aus dem Gefängnis §cgeflohen §fund offiziell Vogelfrei.");
        Bukkit.getServer().broadcastMessage(Main.getChatPrefix() + "§fMehr Infos gibts mit§8: §a/wanted");
        refreshPlayerName(player);
        return false;
    }


    public static void startJailWatcherTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), () -> {
            long now = System.currentTimeMillis();

            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
                if (pco == null) continue;

                // Wanted-Set in Sync mit Cache halten
                if (pco.isWanted()) {
                    wantedPlayers.add(player.getUniqueId());
                } else {
                    wantedPlayers.remove(player.getUniqueId());
                }

                if (pco.isJail()) {
                    long jailEnd = pco.getJailEnd();
                    if (jailEnd > 0 && now >= jailEnd) {
                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> releasePlayer(player));
                    }
                }
            }

        }, 20L, 20L * 30);
    }


    private static Location getRandomJailCell() {
        if (jailCorner1 == null) return null;
        Location cell = jailCorner1.clone();
        return cell;
    }


    public static boolean isInJailArea(Location loc) {
        if (loc == null || jailCorner1 == null || jailCorner2 == null) return false;

        World w = jailCorner1.getWorld();
        if (w == null) return false;
        if (!w.equals(loc.getWorld())) return false;

        double minX = Math.min(jailCorner1.getX(), jailCorner2.getX());
        double maxX = Math.max(jailCorner1.getX(), jailCorner2.getX());
        double minY = Math.min(jailCorner1.getY(), jailCorner2.getY());
        double maxY = Math.max(jailCorner1.getY(), jailCorner2.getY());
        double minZ = Math.min(jailCorner1.getZ(), jailCorner2.getZ());
        double maxZ = Math.max(jailCorner1.getZ(), jailCorner2.getZ());

        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    private static Location getSafeReleaseLocation(@NotNull Player player) {
        return Locations.getLocation2025Location();
    }
}
