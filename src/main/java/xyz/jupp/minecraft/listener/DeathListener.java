package xyz.jupp.minecraft.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Team;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.items.KeepInventoryItem;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Locations;

import java.util.Objects;


public class DeathListener implements Listener {

    private final static Location corner1 = new Location(Bukkit.getWorld("world_MCWinter"), 150144.0D, 290.0D, 150218.0D);
    private final static Location corner2 = new Location(Bukkit.getWorld("world_MCWinter"), 150106.0D, 220.0D, 150257.0D);
    private final static int killCost = 250;

    private static boolean isPlayerInArena(Player player) {
        if (!player.getLocation().getWorld().getName().equals("world_MCWinter")) {
            return false;
        }
        Location playerLocation = player.getLocation();
        double minX = Math.min(corner1.getX(), corner2.getX());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());

        double playerX = playerLocation.getX();
        double playerY = playerLocation.getY();
        double playerZ = playerLocation.getZ();

        return (playerX >= minX && playerX <= maxX) && (playerY >= minY && playerY <= maxY) && (playerZ >= minZ && playerZ <= maxZ);
    }


    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void atDying(PlayerDeathEvent event) {
        Player player = event.getEntity();

        Player killer = player.getKiller();

        if (isPlayerInArena(player)) {
            event.setShowDeathMessages(false);
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.getDrops().clear();
            event.setDroppedExp(0);

            String msg;
            if (killer != null) {
                msg = Main.getChatPrefix() + "§a" + player.getName() + " §fwurde von §c" + killer.getName() + " §fin der Arena besiegt!";
            } else {
                msg = Main.getChatPrefix() + "§a" + player.getName() + " §fist in der Arena gestorben.";
            }
            Bukkit.broadcastMessage(msg);
            player.sendMessage(Main.getChatPrefix() + "§aDu bist in der Arena gestorben und behältst daher deine Items und Level.");
            return;
        }

        KeepInventoryItem keepInventoryItem = new KeepInventoryItem();

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.CHEST) continue;

            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            if (meta.getPersistentDataContainer().has(keepInventoryItem.getKey(), PersistentDataType.BYTE)) {
                event.setKeepInventory(true);
                event.setKeepLevel(true);
                event.getDrops().clear();
                event.setDroppedExp(0);

                item.setAmount(0);
                break;
            }

            if (meta.hasDisplayName() && ChatColor.stripColor(meta.getDisplayName())
                    .equalsIgnoreCase(ChatColor.stripColor(keepInventoryItem.getItemName()))) {

                event.setKeepInventory(true);
                event.setKeepLevel(true);
                event.getDrops().clear();
                event.setDroppedExp(0);

                item.setAmount(0);
                break;
            }
        }


        Location deathLoc = player.getLocation();
        player.sendMessage(Main.getChatPrefix() + "§fDein Todesort » §8x: §a" + Math.round(deathLoc.getX()) + " §8y: §a" + Math.round(deathLoc.getY()) + " §8z: §a" + Math.round(deathLoc.getZ()));

        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            float deathTaxRate = ConfigManager.getManager().getDeathTax();
            PlayerCollection playerCollection = new PlayerCollection(player);
            int money = playerCollection.getMoney();

            if (money <= 250) {
                player.sendMessage(Main.getChatPrefix() + "Dir wurde §ckeine §fTodes-Steuer berechnet.");
            }else {
                int tax = Math.round(money * deathTaxRate);
                int updatedMoney = money - tax;
                playerCollection.updateMoney(updatedMoney);

                player.sendMessage(String.format(
                        "%sDir wurden §a%s §8(§2%.0f%%§8) §fals Todes-Steuer berechnet.",
                        Main.getChatPrefix(),
                        Main.getCurrencyName(tax),
                        deathTaxRate * 100
                ));
            }

            if (killer != null) {
                PlayerCacheObject killerPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(killer);
                PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);

                if (player.getPlayerListName().startsWith(Main.getIsWantedPrefix())) {
                    player.playSound(player.getLocation(), Sound.BLOCK_DEADBUSH_IDLE, 1, 1);
                    playerCacheObject.setWanted(false);
                    playerCacheObject.setJail(true, 72);
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        player.teleport(Locations.getJailSpawn());
                    });

                    if (killerPlayerCacheObject.getTeamID() != null &&
                            killerPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                        killer.sendMessage(Main.getChatPrefix() + "§cDu kannst keine Belohnung von deinem Teamteamkollegen eintreiben.");
                        return;
                    }

                    killer.sendMessage(Main.getChatPrefix() + "§aDu hast den gesuchten Spieler §6" + player.getName() + " §agefunden!");
                    if (JailHandler.getAlreadyKilledPlayer().contains(player.getUniqueId())) return;

                    killer.sendMessage(Main.getChatPrefix() + "§fHier deine Belohnung!");
                    killer.sendMessage(Main.getChatPrefix() + " ");

                    int currentMoney = killerPlayerCacheObject.getPlayerCollection().getMoney();
                    killerPlayerCacheObject.getPlayerCollection().updateMoney(currentMoney + 10000);
                    killer.sendMessage(Main.getChatPrefix() + "§a+" + Main.getCurrencyName(10000));

                    if (killerPlayerCacheObject.getTeamID() != null) {
                        int currentTeamPoints = killerPlayerCacheObject.getTeamCacheObject().getTeamCollection().getTeamPoints();
                        killerPlayerCacheObject.getTeamCacheObject().getTeamCollection().changeTeamPoints(currentTeamPoints + 1000);
                        PlayerCacheObject tmpPco;
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            tmpPco = CacheHandler.getInstance().getPlayerInCache(p);
                            if (tmpPco.getTeamID() != null &&  tmpPco.getTeamID().equals(killerPlayerCacheObject.getTeamID())) {
                                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
                                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
                                p.sendMessage(Main.getChatPrefix() + "§a+1000 Team-Punkte §ffür das Eintreiben des Kopfgeldes von §a"+player.getName());
                            }
                        }
                    }

                    JailHandler.getAlreadyKilledPlayer().add(player.getUniqueId());
                    player.sendMessage(Main.getChatPrefix() + "§cWenn dich das Gesetz nicht holt, §a" + Main.getCurrencyName() + "e §ctun es.");
                    player.sendMessage(Main.getChatPrefix() + "§6Deine Haft wurde auf §c72h §6festgesetzt.");

                    return;
                }

                if (killerPlayerCacheObject.getTeamID() == null || playerCacheObject.getTeamID() == null) return;

                int targetTeamPoints = killerPlayerCacheObject.getTeamCacheObject().getTeamCollection().getTeamPoints();
                int playerTeamPoints = playerCacheObject.getTeamCacheObject().getTeamCollection().getTeamPoints();

                killerPlayerCacheObject.getTeamCacheObject().getTeamCollection().changeTeamPoints(targetTeamPoints + killCost);
                int earnedPoints = killCost;
                if (playerTeamPoints < killCost) {
                    playerCacheObject.getTeamCacheObject().getTeamCollection().changeTeamPoints(0);
                    earnedPoints = playerTeamPoints;
                    playerCacheObject.getTeamCacheObject().downgradeTeamLevel();


                }else {
                    playerCacheObject.getTeamCacheObject().getTeamCollection().changeTeamPoints(playerTeamPoints - killCost);
                }

                PlayerCacheObject tmpPlayerCacheObject;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(online);
                    if (tmpPlayerCacheObject.getTeamID() == null) continue;
                    if (tmpPlayerCacheObject.getTeamID().equals(killerPlayerCacheObject.getTeamID())) {
                        online.sendMessage(Main.getChatPrefix() + "§a+" + earnedPoints + " Team-Punkte §ffür den Kill an " + player.getDisplayName());
                    }
                    if (tmpPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                        online.sendMessage(Main.getChatPrefix() + "§c-" + earnedPoints + " Team-Punkte §fwegen dem Tod durch " + killer.getDisplayName());
                        if (earnedPoints < 250) {
                            online.sendMessage(Main.getChatPrefix() + "§cEuer Team wurde ein Level herunter gestuft!");
                            online.sendMessage(Main.getChatPrefix() + "§fAchtet in Zukunft immer auf genügend Team-Punkte!");
                            online.sendMessage(" ");
                            online.sendMessage("§f§oEure Optionen im Gebiets-Manager wurden zurückgesetzt.");
                        }
                    }
                }

            }else {

                PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                if (playerCacheObject.isWanted()){
                    player.playSound(player.getLocation(), Sound.BLOCK_DEADBUSH_IDLE, 1, 1);
                    playerCacheObject.setWanted(false);
                    playerCacheObject.setJail(true, 72);
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        player.teleport(Locations.getJailSpawn());
                    });

                    player.sendMessage(Main.getChatPrefix() + "§cManchmal hat man eben einfach Unglück.");
                    player.sendMessage(Main.getChatPrefix() + "§6Deine Haft wurde auf §c72h §6festgesetzt.");
                }

            }

        });
    }

    @EventHandler
    public void onDyingEntity(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        Entity entity = event.getEntity();
        if (!entity.isCustomNameVisible()) return;
        if (entity.getCustomName() != null && entity.getCustomName().equals("§c§lMonster-Event")) {

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(killer);
                if (playerCacheObject.getTeamID() == null) return;

                TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
                int teamPoints = teamCacheObject.getTeamCollection().getTeamPoints();
                teamCacheObject.getTeamCollection().changeTeamPoints(teamPoints + 10);

                PlayerCacheObject tmpPlayerCacheObject;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(online);
                    if (tmpPlayerCacheObject.getTeamID() == null) continue;
                    if (tmpPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                        online.sendMessage(Main.getChatPrefix() + "§a+10 Team-Punkte");
                        online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 0.2f);
                    }

                }

            });

        }

    }


    @EventHandler
    public void atRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerCacheObject cache = CacheHandler.getInstance().getPlayerInCache(player);

        if (cache == null) {
            player.setPlayerListName(player.getName());
            player.setDisplayName(player.getName());
            return;
        }

        String listName;
        String displayName;

        if (cache.getTeamID() == null || cache.getTeamCacheObject() == null) {
            listName = "§a" + player.getName();
            displayName = listName;
        } else {
            var team = cache.getTeamCacheObject();
            String color = team.getTeamColor();

            if (team.getTeamOwner().equals(player.getUniqueId().toString())) {
                listName = color + "§l" + player.getName();
                displayName = listName;
            } else if (team.getTeamVices().contains(player.getUniqueId().toString())) {
                listName = color + "§o" + player.getName();
                displayName = listName;
            } else {
                listName = color + player.getName();
                displayName = listName;
            }
        }

        if (cache.isWanted()) {
            listName = Main.getIsWantedPrefix() + listName.replace(Main.getInJailPrefix(), "");
            displayName = Main.getIsWantedPrefix() + displayName.replace(Main.getInJailPrefix(), "");
            event.setRespawnLocation(Locations.getJailSpawn());
        } else if (cache.isJail()) {
            listName = Main.getInJailPrefix() + listName.replace(Main.getIsWantedPrefix(), "");
            displayName = Main.getInJailPrefix() + displayName.replace(Main.getIsWantedPrefix(), "");
            event.setRespawnLocation(Locations.getJailSpawn());
        } else if (isPlayerInArena(player)) {
            event.setRespawnLocation(Locations.getCurrentSpawn());
        }

        player.setPlayerListName(listName);
        player.setDisplayName(displayName);

    }


}
