package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

public class PlayerHeadsCommand implements CommandExecutor {

    private static final int HEAD_PRICE = 100;

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player player) {

            if (args.length == 0) {
                player.sendMessage(Main.getChatPrefix() + "Bitte nutze: §a/head <Name> §f(" + Main.getCurrencyName(HEAD_PRICE) + "§f)");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return false;
            }

            String targetName = args[0];
            Tasks.async(() -> {
                if (PlayerRepository.getMoney(player) < HEAD_PRICE) {
                    Tasks.sync(() -> sendTooExpensive(player));
                    return;
                }

                // built here, the owner lookup by name may block
                ItemStack playerHead = createHead(targetName);

                if (!PlayerRepository.tryWithdrawMoney(player, HEAD_PRICE)) {
                    Tasks.sync(() -> sendTooExpensive(player));
                    return;
                }

                try {
                    Tasks.sync(() -> {
                        player.getInventory().addItem(playerHead);

                        player.sendMessage(Main.getChatPrefix() + "Du hast den Kopf von §a" + targetName + " §fgekauft.");
                        player.sendMessage(Main.getChatPrefix() + "§c-" + HEAD_PRICE + " " + Main.getCurrencyName());
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                    });
                } catch (IllegalPluginAccessException e) {
                    // the plugin is being disabled, the head can no longer be handed out
                    PlayerRepository.addMoney(player, HEAD_PRICE);
                }
            });
        }
        return false;
    }

    private static ItemStack createHead(@NotNull String targetName) {
        ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();
        playerHeadMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetName));
        playerHeadMeta.customName(Text.of("§a" + targetName));
        playerHead.setItemMeta(playerHeadMeta);
        return playerHead;
    }

    private static void sendTooExpensive(@NotNull Player player) {
        player.sendMessage(Main.getChatPrefix() + "Ein §aCustomHead §fkostet " + Main.getCurrencyName(HEAD_PRICE) + "§f.");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
    }
}
