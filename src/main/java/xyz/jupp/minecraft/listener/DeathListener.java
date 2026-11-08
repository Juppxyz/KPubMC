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
import xyz.jupp.minecraft.items.KeepInventoryItem;
import xyz.jupp.minecraft.jail.Bounties;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.UUID;
import java.util.function.Consumer;


// The database work after a death runs on a worker thread, everything that touches players runs on the main thread.
public class DeathListener implements Listener {

    // the listener is created in onEnable, so the plugin instance exists here
    private static final KeepInventoryItem KEEP_INVENTORY_ITEM = new KeepInventoryItem();

    static boolean isPlayerInArena(Player player) {
        return Locations.isInArena(player.getLocation());
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
                // one box per death, the rest of a stack stays
                item.setAmount(item.getAmount() - 1);
                break;
            }
        }


        Location deathLoc = player.getLocation();
        player.sendMessage(Main.getChatPrefix() + "§fDein Todesort » §7x: §a" + Math.round(deathLoc.getX()) + " §7y: §a" + Math.round(deathLoc.getY()) + " §7z: §a" + Math.round(deathLoc.getZ()));

        // a wanted player killed by someone outside their team drops the head, the Sheriff pays the bounty for it
        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
        boolean wanted = playerCacheObject.isWanted();
        // read before the jail sentence replaces it: 0 = wanted until caught
        long wantedEnd = playerCacheObject.getJailEnd();
        String playerTeamID = playerCacheObject.getTeamID();
        String killerTeamID = killer == null ? null : CacheHandler.getInstance().getPlayerInCache(killer).getTeamID();
        boolean bounty = wanted && killer != null && killer != player && (playerTeamID == null || !playerTeamID.equals(killerTeamID));
        UUID bountyID = bounty ? UUID.randomUUID() : null;
        if (bounty) event.getDrops().add(Bounties.head(bountyID, player, playerTeamID, killer.getName()));

        Tasks.async(() -> {
            chargeDeathTax(player);

            if (wanted) {
                jailWanted(player, killer, bountyID, playerTeamID, killerTeamID, wantedEnd);
                return;
            }
            if (killer == null) return;
            settleTeamKill(player, killer, playerCacheObject, CacheHandler.getInstance().getPlayerInCache(killer));
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
                    "%sDir wurden §a%s §7(§2%.0f%%§7) §fals Todes-Steuer berechnet.",
                    Main.getChatPrefix(),
                    Main.getCurrencyName(tax.tax()),
                    Taxes.deathRate() * 100
            );
        }
        sync(() -> player.sendMessage(message));
    }

    // worker thread: the wanted player goes to jail; with a bounty (bountyID) the head dropped and a hunter caught them
    private static void jailWanted(Player player, Player killer, UUID bountyID, String playerTeamID, String killerTeamID, long wantedEnd) {
        JailHandler.jailAfterDeath(player, bountyID != null);
        String sentence = Main.getChatPrefix() + "§6Deine Haft wurde auf §c" + JailHandler.DEATH_SENTENCE_HOURS + "h §6festgesetzt.";
        if (killer == null) {
            sync(() -> {
                player.playSound(player.getLocation(), Sound.BLOCK_DEADBUSH_IDLE, 1, 1);
                player.sendMessage(Main.getChatPrefix() + "§cManchmal hat man eben einfach Unglück.");
                player.sendMessage(sentence);
            });
            return;
        }
        if (bountyID == null) {
            sync(() -> {
                killer.sendMessage(Main.getChatPrefix() + "§cFür deinen Teamkollegen gibt es kein Kopfgeld.");
                player.sendMessage(sentence);
            });
            return;
        }
        // catching a wanted enemy is a kill in a war as well
        if (killerTeamID != null && playerTeamID != null) Relations.warKill(killerTeamID, playerTeamID);
        Bounties.record(bountyID, player.getUniqueId(), player.getName(), playerTeamID, killer.getUniqueId(), Loans.bounty(player.getUniqueId(), wantedEnd));
        sync(() -> {
            killer.sendMessage(Main.getChatPrefix() + "§aDu hast den gesuchten Spieler §6" + player.getName() + " §agefasst!");
            killer.sendMessage(Main.getChatPrefix() + "§fBring den Kopf zum §9Sheriff§f, dort gibt es das Kopfgeld.");
            killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
            player.playSound(player.getLocation(), Sound.BLOCK_DEADBUSH_IDLE, 1, 1);
            player.sendMessage(Main.getChatPrefix() + "§cWenn dich das Gesetz nicht holt, §a" + Main.getCurrencyName() + "e §ctun es.");
            player.sendMessage(sentence + " §7(Diesmal ohne Flucht.)");
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
        String war = !ownTeam && Relations.warKill(killerTeamID, playerTeamID) ? " §7(Krieg)" : "";
        sync(() -> {
            if (!ownTeam) {
                forEachOnlineTeamMember(killerTeamID, online ->
                        online.sendMessage(Main.getChatPrefix() + "§a+" + penalty.taken() + " Team-Punkte §ffür den Kill an " + player.getDisplayName() + war));
            }
            forEachOnlineTeamMember(playerTeamID, online -> {
                online.sendMessage(Main.getChatPrefix() + "§c-" + penalty.taken() + " Team-Punkte §fwegen dem Tod durch " + killer.getDisplayName() + war);
                if (penalty.levelsLost() > 0) {
                    online.sendMessage(Main.getChatPrefix() + "§cDie Punkte reichten nicht: euer Team ist jetzt Level " + penalty.newLevel()
                            + "§c. §7(Die Stufe brachte " + penalty.refunded() + " Punkte zurück.)");
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

        // a wanted player who died is on the way to jail (JailHandler.jailAfterDeath), prisoners stay in their cell;
        // a death in the arena jails nobody (atDying), so a wanted player respawns at the spawn from there
        boolean arena = isPlayerInArena(player);
        if (cache.isJail() || cache.isWanted()) name = (cache.isJail() ? Main.getInJailPrefix() : Main.getIsWantedPrefix()) + name;
        if (cache.isJail() || (cache.isWanted() && !arena)) {
            Location cell = JailHandler.cellSpawn(cache);
            if (cell != null) event.setRespawnLocation(cell);
        } else if (arena) {
            event.setRespawnLocation(Locations.getCurrentSpawn());
        }

        player.playerListName(Text.listName(name, player.getName()));
        player.setDisplayName(name);

    }


}
