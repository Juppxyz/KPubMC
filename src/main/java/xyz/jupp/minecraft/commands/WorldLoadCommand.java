package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

import java.util.Locale;

/**
 * /mvload <Welt>: loads a world folder until the next restart, admins only. A name without a folder creates a new world.
 * Nether and End are guessed from the old Bukkit suffixes _nether and _the_end.
 */
public class WorldLoadCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(Main.getChatPrefix() + "§fSo: §a/" + label + " <Welt>");
            return true;
        }
        if (Bukkit.getWorld(args[0]) != null) {
            sender.sendMessage(Main.getChatPrefix() + "§fDie Welt §e" + args[0] + " §fist schon geladen.");
            return true;
        }
        String lower = args[0].toLowerCase(Locale.ENGLISH);
        World.Environment environment = lower.endsWith("_nether") ? World.Environment.NETHER
                : lower.endsWith("_the_end") ? World.Environment.THE_END : World.Environment.NORMAL;
        sender.sendMessage(Main.getChatPrefix() + "§fLade §e" + args[0] + "§f ...");
        World world;
        try {
            world = new WorldCreator(args[0]).environment(environment).createWorld();
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().error("Could not load world {}", args[0], e);
            world = null;
        }
        if (world == null) {
            sender.sendMessage(Main.getChatPrefix() + "§cDie Welt §e" + args[0] + " §cwurde nicht geladen, siehe Konsole.");
            return true;
        }
        sender.sendMessage(Main.getChatPrefix() + "§fWelt §e" + world.getName() + " §fgeladen. §7(/mvtp " + world.getName() + ")");
        return true;
    }

}
