package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.team.TeamCreateView;
import xyz.jupp.minecraft.team.TeamView;
import xyz.jupp.minecraft.team.Teams;
import xyz.jupp.minecraft.utils.Tasks;

// /team opens the team menu, /team neu <Name> founds a team
public class TeamCommand implements CommandExecutor {

    private record Check(int money, boolean taken) {}

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (!(commandSender instanceof Player player)) return true;
        boolean inTeam = CacheHandler.getInstance().getPlayerInCache(player).getTeamID() != null;

        if (args.length >= 1 && args[0].equalsIgnoreCase("neu")) {
            if (inTeam) {
                fail(player, "Du bist schon in einem Team.");
            } else if (args.length != 2) {
                fail(player, "So geht's: §a/team neu <Name>");
            } else if (player.getLevel() < Teams.CREATION_LEVEL) {
                fail(player, "Du brauchst §a" + Teams.CREATION_LEVEL + " §fLevel, um ein Team zu gründen §7(sie werden nicht abgezogen)§f.");
            } else if (!TeamCreateView.NAME.matcher(args[1]).matches()) {
                fail(player, "Der Name braucht §a" + Teams.NAME_MIN + " bis " + Teams.NAME_MAX + " §fZeichen: Buchstaben, Zahlen, _ oder -.");
            } else {
                String name = args[1];
                Tasks.supplyAsync(() -> new Check(PlayerRepository.getMoney(player.getUniqueId()), Teams.nameTaken(name)), check -> {
                    if (!player.isOnline()) return;
                    if (check.taken()) {
                        fail(player, "Den Namen §e" + name + " §fgibt es schon.");
                    } else if (check.money() < Teams.CREATION_COST) {
                        fail(player, "Das Gründen kostet " + Main.getCurrencyName(Teams.CREATION_COST) + "§f.");
                    } else {
                        TeamCreateView.open(player, name);
                    }
                });
            }
            return true;
        }

        if (!inTeam) {
            player.sendMessage(Main.getChatPrefix() + "Du bist noch in keinem " + Main.getTeamName() + "§f.");
            player.sendMessage(Main.getChatPrefix() + "Gründe eins mit §a/team neu <Name> §7(ab Level " + Teams.CREATION_LEVEL
                    + ", " + Teams.CREATION_COST + " Schilling)§f, oder lass dich aufnehmen: §a/invites§f.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return true;
        }
        TeamView.open(player);
        return true;
    }

    private static void fail(Player player, String message) {
        player.sendMessage(Main.getChatPrefix() + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
    }

}
