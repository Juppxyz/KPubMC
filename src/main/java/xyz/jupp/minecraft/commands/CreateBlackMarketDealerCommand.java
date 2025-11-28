package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class CreateBlackMarketDealerCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            if (!PermissionsUtil.isPlayerAdmin(player)){
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            }

            Vindicator vindicator = (Vindicator) player.getLocation().getWorld().spawnEntity(player.getLocation(), EntityType.VINDICATOR);
            vindicator.setCustomName(Main.getBlackMarketDealerVillagerName());
            vindicator.setCustomNameVisible(false);
            vindicator.setInvulnerable(true);
            vindicator.setAI(false);
            vindicator.setGravity(false);
            vindicator.setCollidable(false);

            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
            player.sendMessage(Main.getChatPrefix() + "Der " + Main.getBlackMarketDealerVillagerName() + " §fwurde §aerfolgreich §ferstellt.");
        }
        return false;
    }


}
