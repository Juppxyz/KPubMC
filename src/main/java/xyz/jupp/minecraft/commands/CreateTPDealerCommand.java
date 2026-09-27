package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class CreateTPDealerCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player player) {
            if (!PermissionsUtil.isPlayerAdmin(player)){
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            }

            WanderingTrader wanderingTrader = NpcSpawner.spawn(player, EntityType.WANDERING_TRADER, WanderingTrader.class, Main.getTeamPointsDealerVillagerName(), true);
            wanderingTrader.setGlowing(true);

            NpcSpawner.confirm(player, "Der " + Main.getTeamPointsDealerVillagerName() + " §fwurde §aerfolgreich §ferstellt.");
        }
        return false;
    }

}
