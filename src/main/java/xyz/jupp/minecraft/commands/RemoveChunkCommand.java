package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.utils.Tasks;

public class RemoveChunkCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] strings) {
        if (commandSender instanceof Player player) {
            PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
            if (!player.isOp() && pco.getTeamID() == null) return false;

            World mainWorld = Bukkit.getWorld("world_MCWinter");
            if (mainWorld == null) return false;

            String teamID = pco.getTeamID();
            String worldName = mainWorld.getName();
            Location currentLocation = player.getLocation();
            int chunkX = currentLocation.getBlockX() >> 4;
            int chunkZ = currentLocation.getBlockZ() >> 4;

            Tasks.supplyAsync(() -> removeClaim(teamID, worldName, chunkX, chunkZ), isChunkRemoved -> {
                if (isChunkRemoved) {
                    player.sendMessage(Main.getChatPrefix() + "§cChunk wurde wieder freigegeben.");
                }else {
                    player.sendMessage(Main.getChatPrefix() + "§aDer Chunk konnte nicht freigegeben werden.");
                }
            });
        }
        return false;

    }

    // blocking (database); one at a time, so a repeated command cannot release the same claim twice
    private static synchronized boolean removeClaim(@Nullable String teamID, @NotNull String worldName, int chunkX, int chunkZ) {
        return ChunkCache.getInstance().removeChunk(teamID, worldName, chunkX, chunkZ);
    }
}
