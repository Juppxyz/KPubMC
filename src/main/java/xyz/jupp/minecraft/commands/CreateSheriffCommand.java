package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class CreateSheriffCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player player) {
            if (!PermissionsUtil.isPlayerAdmin(player)) {
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            }

            Villager villager = NpcSpawner.spawn(player, EntityType.VILLAGER, Villager.class, Main.getSheriffVillagerName(), true);
            villager.setProfession(Villager.Profession.ARMORER);

            NpcSpawner.confirm(player, "Der " + Main.getSheriffVillagerName() + " §fwurde §aerfolgreich §ferstellt.");
        }
        return false;
    }

}
