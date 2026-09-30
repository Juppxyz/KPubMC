package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.jupp.minecraft.team.Teams;
import xyz.jupp.minecraft.team.Relations;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.Loans;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.database.TeamRepository;
import xyz.jupp.minecraft.items.KeepInventoryItem;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.function.Consumer;


// The database work after a death runs on a worker thread, everything that touches players runs on the main thread.
public class DeathListener implements Listener {

    private static final String ARENA_WORLD = "world_MCWinter";
    private static final double ARENA_MIN_X = 150106.0D;
    private static final double ARENA_MAX_X = 150144.0D;
    private static final double ARENA_MIN_Y = 220.0D;
    private static final double ARENA_MAX_Y = 290.0D;
    private static final double ARENA_MIN_Z = 150218.0D;
    private static final double ARENA_MAX_Z = 150257.0D;

    // the listener is created in onEnable, so the plugin instance exists here
    private static final KeepInventoryItem KEEP_INVENTORY_ITEM = new KeepInventoryItem();

    private static boolean isPlayerInArena(Player player) {
        Location location = player.getLocation();
        if (!location.getWorld().getName().equals(ARENA_WORLD)) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return (x >= ARENA_MIN_X && x <= ARENA_MAX_X) && (y >= ARENA_MIN_Y && y <= ARENA_MAX_Y) && (z >= ARENA_MIN_Z && z <= ARENA_MAX_Z);
    }

    private static void keepInventory(PlayerDeathEvent event) {
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    private static boolean isKeepInventoryItem(ItemMeta meta) {
        return meta.getPersistentDataContainer().has(KEEP_INVENTORY_ITEM.getKey(), PersistentDataType.BYTE);
    }

    // from a worker: a death during /stop still finishes its database work, only the main-thread part is dropped
    private static void sync(Runnable task) {
        if (Main.getInstance().isEnabled()) Tasks.sync(task);
    }

    // main thread only
    private static void forEachOnlineTeamMember(String teamID, Consumer<Player> action) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (teamID.equals(CacheHandler.getInstance().getPlayerInCache(online).getTeamID())) {
                action.accept(online);
            }
        }
    }


    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void atDying(PlayerDeathEvent event) {
        Player player = event.getEntity();

        Player killer = player.getKiller();

        if (isPlayerInArena(player)) {
            event.setShowDeathMessages(false);
            keepInventory(event);

            String msg;
            if (killer != null) {
                msg = Main.getChatPrefix() + "§a" + player.getName() + " §fwurde von §c" + killer.getName() + " §fin der Arena besiegt!";
            } else {
                msg = Main.getChatPrefix() + "§a" + player.getName() + " §fist in der Arena gestorben.";
            }
            Bukkit.broadcast(Text.section(msg));
            player.sendMessage(Main.getChatPrefix() + "§aDu bist in der Arena gestorben und behältst daher deine Items und Level.");
            return;
        }

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.CHEST) continue;

            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            if (isKeepInventoryItem(meta)) {
                keepInventory(event);
                item.setAmount(0);
                break;
            }
        }


        Location deathLoc = player.getLocation();
        player.sendMessage(Main.getChatPrefix() + "§fDein Todesort » §8x: §a" + Math.round(deathLoc.getX()) + " §8y: §a" + Math.round(deathLoc.getY()) + " §8z: §a" + Math.round(deathLoc.getZ()));

        // the bounty is recognised by the prefix in the tab list name
        boolean listedAsWanted = killer != null && Text.legacy(player.playerListName()).startsWith(Main.getIsWantedPrefix());

        Tasks.async(() -> {
            chargeDeathTax(player);

            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            if (killer == null) {
                if (!playerCacheObject.isWanted()) return;
                jailAfterDeath(player, playerCacheObject);
                sync(() -> {
                    player.sendMessage(Main.getChatPrefix() + "§cManchmal hat man eben einfach Unglück.");
                    player.sendMessage(Main.getChatPrefix() + "§6Deine Haft wurde auf §c72h §6festgesetzt.");
                });
                return;
            }

            PlayerCacheObject killerPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(killer);
            if (listedAsWanted) {
                collectBounty(player, killer, playerCacheObject, killerPlayerCacheObject);
            } else {
                settleTeamKill(player, killer, playerCacheObject, killerPlayerCacheObject);
            }
        });
    }

    // worker thread
    private static void chargeDeathTax(Player player) {
        Taxes.BalanceTax tax = Taxes.chargeDeathTax(player.getUniqueId());

        String message;
        if (tax.tax() == 0) {
            message = Main.getChatPrefix() + "Dir wurde §ckeine §fTodes-Steuer berechnet.";
        } else {
            message = String.format(
                    "%sDir wurden §a%s §8(§2%.0f%%§8) §fals Todes-Steuer berechnet.",
                    Main.getChatPrefix(),
                    Main.getCurrencyName(tax.tax()),
                    Taxes.deathRate() * 100
            );
        }
        sync(() -> player.sendMessage(message));
    }

    // worker thread: the wanted player goes to jail for 72 h
    private static void jailAfterDeath(Player player, PlayerCacheObject playerCacheObject) {
        sync(() -> player.playSound(player.getLocation(), Sound.BLOCK_DEADBUSH_IDLE, 1, 1));
        playerCacheObject.setWanted(false);
        playerCacheObject.setJail(true, 72);
        sync(() -> player.teleport(Locations.getJailSpawn()));
    }

    // worker thread
    private static void collectBounty(Player player, Player killer, PlayerCacheObject playerCacheObject, PlayerCacheObject killerPlayerCacheObject) {
        jailAfterDeath(player, playerCacheObject);

        String killerTeamID = killerPlayerCacheObject.getTeamID();
        if (killerTeamID != null && killerTeamID.equals(playerCacheObject.getTeamID())) {
            sync(() -> killer.sendMessage(Main.getChatPrefix() + "§cDu kannst keine Belohnung von deinem Teamteamkollegen eintreiben."));
            return;
        }
        // catching a wanted enemy is a kill in a war as well
        String victimTeamID = playerCacheObject.getTeamID();
        if (killerTeamID != null && victimTeamID != null) Relations.warKill(killerTeamID, victimTeamID);

        boolean alreadyCollected = JailHandler.getAlreadyKilledPlayer().contains(player.getUniqueId());
        sync(() -> {
            killer.sendMessage(Main.getChatPrefix() + "§aDu hast den gesuchten Spieler §6" + player.getName() + " §agefunden!");
            if (alreadyCollected) return;
            killer.sendMessage(Main.getChatPrefix() + "§fHier deine Belohnung!");
            killer.sendMessage(Main.getChatPrefix() + " ");
        });
        if (alreadyCollected) return;

        int reward = Loans.bounty(player.getUniqueId());
        if (reward > 0) PlayerRepository.addMoney(killer, reward);
        sync(() -> killer.sendMessage(Main.getChatPrefix() + "§a+" + Main.getCurrencyName(reward)));

        if (killerTeamID != null) {
            TeamRepository.addTeamPoints(killerTeamID, 1000);
            sync(() -> forEachOnlineTeamMember(killerTeamID, p -> {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
                p.sendMessage(Main.getChatPrefix() + "§a+1000 Team-Punkte §ffür das Eintreiben des Kopfgeldes von §a" + player.getName());
            }));
        }

        JailHandler.getAlreadyKilledPlayer().add(player.getUniqueId());
        sync(() -> {
            player.sendMessage(Main.getChatPrefix() + "§cWenn dich das Gesetz nicht holt, §a" + Main.getCurrencyName() + "e §ctun es.");
            player.sendMessage(Main.getChatPrefix() + "§6Deine Haft wurde auf §c72h §6festgesetzt.");
        });
    }

    // worker thread
    private static void settleTeamKill(Player player, Player killer, PlayerCacheObject playerCacheObject, PlayerCacheObject killerPlayerCacheObject) {
        String killerTeamID = killerPlayerCacheObject.getTeamID();
        String playerTeamID = playerCacheObject.getTeamID();
        if (killerTeamID == null || playerTeamID == null) return;

        TeamCacheObject killerTeam = killerPlayerCacheObject.getTeamCacheObject();
        TeamCacheObject playerTeam = playerCacheObject.getTeamCacheObject();
        if (killerTeam == null || playerTeam == null) return;

        // partners never fight: a death between them (TNT, a wolf, ...) moves nothing
        if (Relations.partners(killerTeamID, playerTeamID)) return;
        boolean ownTeam = killerTeamID.equals(playerTeamID);
        // the dead member's team pays; without enough points it sells a level back; the killer's team gets what was paid
        Teams.Penalty penalty = Teams.deathPenalty(playerTeam, ownTeam ? null : killerTeamID);
        if (penalty == null) return;
        // a kill keeps a war between the two teams going
        String war = !ownTeam && Relations.warKill(killerTeamID, playerTeamID) ? " §8(Krieg)" : "";
        sync(() -> {
            if (!ownTeam) {
                forEachOnlineTeamMember(killerTeamID, online ->
                        online.sendMessage(Main.getChatPrefix() + "§a+" + penalty.taken() + " Team-Punkte §ffür den Kill an " + player.getDisplayName() + war));
            }
            forEachOnlineTeamMember(playerTeamID, online -> {
                online.sendMessage(Main.getChatPrefix() + "§c-" + penalty.taken() + " Team-Punkte §fwegen dem Tod durch " + killer.getDisplayName() + war);
                if (penalty.levelsLost() > 0) {
                    online.sendMessage(Main.getChatPrefix() + "§cDie Punkte reichten nicht: euer Team ist jetzt Level " + penalty.newLevel()
                            + "§c. §8(Die Stufe brachte " + penalty.refunded() + " Punkte zurück.)");
                }
            });
        });
    }

    @EventHandler
    public void atRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerCacheObject cache = CacheHandler.getInstance().getPlayerInCache(player);

        TeamCacheObject team = cache.getTeamID() == null ? null : cache.getTeamCacheObject();
        String name = JoinQuitListener.teamPlayerName(player, team, true);

        if (cache.isWanted()) {
            name = Main.getIsWantedPrefix() + name;
            event.setRespawnLocation(Locations.getJailSpawn());
        } else if (cache.isJail()) {
            name = Main.getInJailPrefix() + name;
            event.setRespawnLocation(Locations.getJailSpawn());
        } else if (isPlayerInArena(player)) {
            event.setRespawnLocation(Locations.getCurrentSpawn());
        }

        player.playerListName(Text.listName(name, player.getName()));
        player.setDisplayName(name);

    }


}
