package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.inventory.TeamInventory;


public class TeamCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player) {
            Player player = (Player)commandSender;
            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);

            // open create new Team Inventory
            if (playerCacheObject.getTeamID() == null && args.length == 2 && args[0].equals("neu")) {
                if (player.getExpToLevel() < 40) {
                    player.sendMessage(Main.getChatPrefix() + "Du brauchst §a40 §fLevel, um ein Team zu erstellen.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    return false;
                }

                if (args[1].length() < 3 || args[1].length() > 12) {
                    player.sendMessage(Main.getChatPrefix() + "Bitte achte darauf, dass der Name des Teams §amin. 3 §fund §amax. 12 §fZeichen lang ist.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    return false;
                }

                PlayerCollection playerCollection = new PlayerCollection(player);
                int money = playerCollection.getMoney();
                if (money < 2500) {
                    player.sendMessage(Main.getChatPrefix() + "Das gründen eines Teams kostet " + Main.getCurrencyName(2500) + "§f.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    return false;
                }

                TeamInventory.openInventory(player, TeamInventory.TeamInventoryTypes.CREATE, args[1]);
                return false;
            }

            if (playerCacheObject.getTeamID() == null) {
                player.sendMessage(Main.getChatPrefix() + "Du bist derzeit in noch keinem " + Main.getTeamName() + ".");
                player.sendMessage(Main.getChatPrefix() + "Dein eigenes Team kannst du so erstellen: §a/team neu <Name>");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return false;
            }

            if (args.length == 1 || args.length > 2) {
                player.sendMessage(Main.getChatPrefix() + "Bitte nutze für Hilfe: §a/team <hilfe/help>");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return false;
            }
            TeamInventory.openInventory(player, TeamInventory.TeamInventoryTypes.MAIN);
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 2f,2f);
        }
        return false;
    }

}
