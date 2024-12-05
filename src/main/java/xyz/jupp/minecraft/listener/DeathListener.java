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

    @EventHandler
    public void atDying(PlayerDeathEvent event) {
        Player player = event.getEntity();
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
    }
}
