package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * /mvtp [Spieler] <Welt>: like the old Multiverse teleport, to the spawn of a loaded world. Admins only.
 */
public class WorldTeleportCommand implements TabExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }
        if (args.length == 0 || args.length > 2) {
            fail(sender, "§fSo: §a/" + label + " [Spieler] <Welt>");
            return true;
        }
        World world = Bukkit.getWorld(args[args.length - 1]);
        if (world == null) {
            fail(sender, "§fDie Welt §e" + args[args.length - 1] + " §fist nicht geladen.");
            return true;
        }
        Player target;
        if (args.length == 2) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                fail(sender, "§e" + args[0] + " §fist nicht online.");
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            fail(sender, "§fVon der Konsole: §a/" + label + " <Spieler> <Welt>");
            return true;
        }

        // COMMAND, not PLUGIN: the Nether tax only follows warps and portals
        target.teleportAsync(world.getSpawnLocation().toCenterLocation().subtract(0, 0.5, 0), PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(done -> {
            if (!done) {
                fail(sender, "§fDer Port wurde abgebrochen.");
                return;
            }
            target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            target.sendMessage(Main.getChatPrefix() + "§fDu bist jetzt in §e" + world.getName() + "§f.");
            if (sender != target) sender.sendMessage(Main.getChatPrefix() + "§e" + target.getName() + " §fist jetzt in §e" + world.getName() + "§f.");
        });
        return true;
    }

    private static void fail(CommandSender sender, String message) {
        sender.sendMessage(Main.getChatPrefix() + message);
        if (sender instanceof Player player) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) return List.of();
        if (args.length > 2) return List.of();
        Stream<String> worlds = Bukkit.getWorlds().stream().map(World::getName);
        Stream<String> options = args.length == 1 ? Stream.concat(worlds, Bukkit.getOnlinePlayers().stream().map(Player::getName)) : worlds;
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

}
