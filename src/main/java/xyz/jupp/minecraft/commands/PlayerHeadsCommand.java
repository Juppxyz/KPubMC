package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerCollection;

public class PlayerHeadsCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;

            if (args.length == 0) {
                player.sendMessage(Main.getChatPrefix() + "Bitte nutze: §a/head <Name> §f(" + Main.getCurrencyName(100) + "§f)");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return false;
            }

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                PlayerCollection playerCollection = new PlayerCollection(player);
                int money = playerCollection.getMoney();

                if (money < 100) {
                    player.sendMessage(Main.getChatPrefix() + "Ein §aCustomHead §fkostet " + Main.getCurrencyName(100) + "§f.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    return;
                }

                String targetName = args[0];
                ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                if (target == null) {
                    player.sendMessage(Main.getChatPrefix() + "§cDer angegebene Spieler konnte nicht gefunden werden.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    return;
                }
                playerHeadMeta.setOwningPlayer(target);
                playerHeadMeta.setDisplayName("§a" + targetName);
                playerHead.setItemMeta(playerHeadMeta);

                playerCollection.updateMoney(money - 100);
                player.getInventory().addItem(playerHead);

                player.sendMessage(Main.getChatPrefix() + "Du hast den Kopf von §a" + targetName + " §fgekauft.");
                player.sendMessage(Main.getChatPrefix() + "§c-100 " + Main.getCurrencyName());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);

            });
        }
        return false;
    }
}
