package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.PlayerCollection;


public class DeathListener implements Listener {

    private final static Location corner1 = new Location(Bukkit.getWorld("world_MCWinter"), 92741.0D, 46.0D, 114509.0D);
    private final static Location corner2 = new Location(Bukkit.getWorld("world_MCWinter"), 92673.0D, 19.0D, 114441.0D);
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


    @EventHandler
    public void atDying(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (isPlayerInArena(player)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            player.sendMessage(Main.getChatPrefix() + "§aDu bist in der Arena gestorben.");
            return;
        }

        Location deathLoc = player.getLocation();
        float deathTaxRate = ConfigManager.getManager().getDeathTax();
        player.sendMessage(Main.getChatPrefix() + "§fDein Todesort » §8x: §a" + Math.round(deathLoc.getX()) + " §8y: §a" + Math.round(deathLoc.getY()) + " §8z: §a" + Math.round(deathLoc.getZ()));
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
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
        });
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
            event.setRespawnLocation(new Location(Bukkit.getWorld("world_MCWinter"), 92696.500D, 69.500D, 114493.500D));
        }
    }
}
