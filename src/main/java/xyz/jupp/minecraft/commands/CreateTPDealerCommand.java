package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class CreateTPDealerCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;
            if (!PermissionsUtil.isPlayerAdmin(player)){
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            }

            WanderingTrader wanderingTrader = (WanderingTrader) player.getLocation().getWorld().spawnEntity(player.getLocation(), EntityType.WANDERING_TRADER);
            wanderingTrader.setCustomName(Main.getTeamPointsDealerVillagerName());
            wanderingTrader.setCustomNameVisible(true);
            wanderingTrader.setInvulnerable(true);
            wanderingTrader.setAI(false);
            wanderingTrader.setGravity(false);
            wanderingTrader.setCollidable(false);
            wanderingTrader.setGlowing(true);

            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
            player.sendMessage(Main.getChatPrefix() + "Der " + Main.getJewelerVillagerName() + " §fwurde §aerfolgreich §ferstellt.");
        }
        return false;
    }

}
