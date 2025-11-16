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
                return;
            }

            int tax = Math.round(money * deathTaxRate);
            int updatedMoney = money - tax;
            playerCollection.updateMoney(updatedMoney);

            player.sendMessage(String.format(
                    "%sDir wurden §a%s §8(§2%.0f%%§8) §fals Todes-Steuer berechnet.",
                    Main.getChatPrefix(),
                    Main.getCurrencyName(tax),
                    deathTaxRate * 100
            ));

            if (killer != null) {
                PlayerCacheObject targetPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(killer);
                PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);

                if (targetPlayerCacheObject.getTeamID() == null || playerCacheObject.getTeamID() == null) return;

                int targetTeamPoints = targetPlayerCacheObject.getTeamCacheObject().getTeamCollection().getTeamPoints();
                int playerTeamPoints = playerCacheObject.getTeamCacheObject().getTeamCollection().getTeamPoints();

                targetPlayerCacheObject.getTeamCacheObject().getTeamCollection().changeTeamPoints(targetTeamPoints + killCost);
                int earnedPoints = killCost;
                if (playerTeamPoints < killCost) {
                    playerCacheObject.getTeamCacheObject().getTeamCollection().changeTeamPoints(0);
                    earnedPoints = playerTeamPoints;
                }else {
                    playerCacheObject.getTeamCacheObject().getTeamCollection().changeTeamPoints(playerTeamPoints - killCost);
                }

                PlayerCacheObject tmpPlayerCacheObject;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(online);
                    if (tmpPlayerCacheObject.getTeamID() == null) continue;
                    if (tmpPlayerCacheObject.getTeamID().equals(targetPlayerCacheObject.getTeamID())) {
                        online.sendMessage(Main.getChatPrefix() + "§a+" + earnedPoints + " Team-Punkte §ffür den Kill an " + player.getDisplayName());
                    }
                    if (tmpPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                        online.sendMessage(Main.getChatPrefix() + "§c-" + earnedPoints + " Team-Punkte §ffür den Kill von " + killer.getDisplayName());
                    }
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
        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
        if (playerCacheObject.getTeamID() == null || playerCacheObject.getTeamCacheObject() == null) {
            player.setPlayerListName("§a" + player.getPlayerListName());
            return;
        }
        player.setPlayerListName(playerCacheObject.getTeamCacheObject().getTeamColor() + player.getName());
        player.setDisplayName(playerCacheObject.getTeamCacheObject().getTeamColor() + player.getName());

        if (isPlayerInArena(player)) {
            event.setRespawnLocation(Locations.getCurrentSpawn());
        }
    }
}
