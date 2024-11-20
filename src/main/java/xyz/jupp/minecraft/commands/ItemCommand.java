package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.items.LightningSword;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class ItemCommand implements CommandExecutor {


    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            if (!PermissionsUtil.isPlayerAdmin(player)) {
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            } else {
                if (args.length == 0) {
                    player.sendMessage(Main.getChatPrefix() + "Mach das: /customItem <itemName>");
                    return false;
                }

                String itemName = args[0].toLowerCase();


                //leave switch for future items
                switch (itemName) {
                    case "sword":
                        // Give the Sword of Lightning
                        LightningSword lightningSword = new LightningSword();
                        player.getInventory().addItem(lightningSword.getSword());
                        player.sendMessage(Main.getChatPrefix() + "Du hast das STURMSCHWERT erhalten!");
                        break;

                    default:
                        player.sendMessage(Main.getChatPrefix() + "Unbekanntes Item: " + itemName);
                        break;
                }


                return true;


            }
        }
        return false;
    }
}