package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;


public class JewelerCommand implements CommandExecutor {

    // NOT IN USE

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (commandSender instanceof Player) {
            Player player = (Player) commandSender;

            if (!PermissionsUtil.isPlayerAdmin(player)){
                PermissionsUtil.sendNoPermMsg(player);
                return false;
            }

            if (args.length != 1) {
                player.sendMessage(Main.getChatPrefix() + "Bitte nutze: §a/jeweler <Name>");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f,1f);
                return false;
            }

            Villager villager = (Villager) player.getLocation().getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
            villager.setCustomName("§5§lJuwelier §5" + args[0]);
            villager.setCustomNameVisible(true);
            villager.setInvulnerable(true);
            villager.setAI(false);
            villager.setGravity(false);
            villager.setCollidable(false);
            villager.setProfession(Villager.Profession.LIBRARIAN);

            player.sendMessage(Main.getChatPrefix() + "Der Juwelier §a" + args[0] + " §fwurde erstellt.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f,1f);
        }
        return false;
    }
}
