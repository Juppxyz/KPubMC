package xyz.jupp.minecraft.commands;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.ChunkCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.team.Teams;
import xyz.jupp.minecraft.utils.Tasks;

// /removechunk: releases the chunk the player stands in (boss or vice of its team, or an operator for any team)
public class RemoveChunkCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] strings) {
        if (!(commandSender instanceof Player player)) return true;
        Location here = player.getLocation();
        String world = here.getWorld().getName();
        int chunkX = here.getBlockX() >> 4;
        int chunkZ = here.getBlockZ() >> 4;
        ChunkCacheObject claim = ChunkCache.getInstance().getClaim(world, chunkX, chunkZ);
        if (claim == null || claim.getTeamID() == null) {
            player.sendMessage(Main.getChatPrefix() + "Dieser Chunk gehört keinem Team.");
            return true;
        }
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(player).getTeamCacheObject();
        boolean ownTeam = team != null && claim.getTeamID().equals(team.getTeamID()) && Teams.role(team, player.getUniqueId()).canManage();
        if (!ownTeam && !player.isOp()) {
            player.sendMessage(Main.getChatPrefix() + "Freigeben können nur Boss und Vize des Teams.");
            return true;
        }
        String teamID = claim.getTeamID();
        Tasks.supplyAsync(() -> ChunkCache.getInstance().removeChunk(teamID, world, chunkX, chunkZ), removed ->
                player.sendMessage(Main.getChatPrefix() + (removed ? "§aDer Chunk ist wieder frei." : "§cDer Chunk konnte nicht freigegeben werden.")));
        return true;
    }

}
