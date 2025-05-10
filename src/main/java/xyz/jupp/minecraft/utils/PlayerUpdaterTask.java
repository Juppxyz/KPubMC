package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamBlockCache;
import xyz.jupp.minecraft.cache.TeamBlockCacheObject;
import xyz.jupp.minecraft.database.TeamBlockCollection;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.listener.TeamBlockListener;

import java.util.ArrayList;

public class PlayerUpdaterTask implements TaskHandler.Tasks {

    private static int xpBoostWave = 0;

    @Override
    public boolean startTask() {
        Bukkit.getScheduler().scheduleAsyncRepeatingTask(Main.getInstance(), () -> {
            ArrayList<String> alreadyCheckedTeamBlocks = new ArrayList<>(12);
            Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "§fupdating players data..");

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {

                for (Player player : Bukkit.getOnlinePlayers()) {
                    PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                    playerCacheObject.updatePlayer();
                    if (playerCacheObject.getTeamID() == null) continue;
                    if (!alreadyCheckedTeamBlocks.contains(playerCacheObject.getTeamID())) {
                        alreadyCheckedTeamBlocks.add(playerCacheObject.getTeamID());
                        TeamBlockCacheObject teamBlockCacheObject = TeamBlockCache.getTeamBlock(playerCacheObject.getTeamID());

                        if (teamBlockCacheObject != null) {
                            TeamBlockCollection teamBlockCollection = new TeamBlockCollection(playerCacheObject.getTeamID());
                            boolean isTeamBlockInDBActive = teamBlockCollection.getTeamBlockData().getBoolean("isActive");
                            if (isTeamBlockInDBActive != teamBlockCacheObject.isActive()) {
                                teamBlockCacheObject.setActive(teamBlockCollection.getTeamBlockData().getBoolean("isActive"));
                            }

                            if (teamBlockCacheObject.isActive()
                                    && !TeamBlockListener.getTeamBlockMaterials().contains(teamBlockCacheObject.getLocation().getBlock().getType().name()) ) {

                                teamBlockCacheObject.setActive(false);
                                CacheHandler.getInstance().incAlreadyPurchased(playerCacheObject);
                                Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "modified teamblock active state for " + playerCacheObject.getTeamID());
                            }
                        }

                    }

                    if (xpBoostWave >= 12) {
                        TeamCollection teamCollection = new TeamCollection(playerCacheObject.getTeamID());
                        TeamBlockCacheObject teamBlockCacheObject = TeamBlockCache.getTeamBlock(teamCollection.getTeamID());
                        if (teamBlockCacheObject == null || !teamBlockCacheObject.isActive()){
                            continue;
                        }
                        int teamPoints = teamCollection.getTeamPoints();
                        if (teamPoints >= 500) {
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                            player.sendMessage(Main.getChatPrefix() + "§aDu hast deinen Team XP-Boost bekommen!");
                            player.giveExpLevels(5);
                        }
                    }
                }
                if (xpBoostWave >= 12){
                    xpBoostWave = 0;
                    return;
                }
                xpBoostWave++;
            });

            Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "§ffinished updating players data");
        }, 0, 20L * 300);
        return false;
    }

}
