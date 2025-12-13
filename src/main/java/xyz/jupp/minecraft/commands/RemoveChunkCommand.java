package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.PlayerCacheObject;

public class RemoveChunkCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] strings) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
            if (!player.isOp() && (pco == null || pco.getTeamID() == null) ) return false;

            Location currentLocation = player.getLocation();
            boolean isChunkRemoved = ChunkCache.getInstance().removeChunk(
                    pco.getTeamID(),
                    Bukkit.getWorld("world_MCWinter").getName(),
                    currentLocation.getChunk().getX(),
                    currentLocation.getChunk().getZ()
            );

            if (isChunkRemoved) {
                player.sendMessage(Main.getChatPrefix() + "§cChunk wurde wieder freigegeben.");
                return false;
            }else {
                player.sendMessage(Main.getChatPrefix() + "§aDer Chunk konnte nicht freigegeben werden.");
                return false;
            }

        }
        return false;

    }
}
