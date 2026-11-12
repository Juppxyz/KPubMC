package xyz.jupp.minecraft.commands;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.jail.Bounties;
import xyz.jupp.minecraft.listener.CreateLocalShopListener;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /kopfgeld <Spieler> <Betrag>: puts money of one's own on a wanted player's head (any amount, plus Bounties.PLEDGE_FEE
 * for the state). Announced without the sponsor's name. The hunter who brings the head to the Sheriff gets it on top of
 * the state's bounty; if nobody does, it goes back.
 */
public class BountyCommand implements CommandExecutor, TabCompleter {

    // one booking per player at a time
    private static final Set<UUID> booking = ConcurrentHashMap.newKeySet();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur für Spieler.");
            return true;
        }
        if (JailHandler.isCaughtPrisoner(player)) {
            fail(player, "§cIm Gefängnis geht das nicht.");
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(Main.getChatPrefix() + "§f/" + label + " <Spieler> <Betrag> §7(auf einen Gesuchten; dazu kostet es "
                    + Bounties.PLEDGE_FEE + " Schilling Gebühr an den Staat)");
            return true;
        }
        int amount = CreateLocalShopListener.parseNumber(args[1]);
        if (amount <= 0) {
            fail(player, "§cDas ist kein gültiger Betrag.");
            return true;
        }
        UUID target;
        String name;
        Player online = Bukkit.getPlayerExact(args[0]);
        if (online != null) {
            target = online.getUniqueId();
            name = online.getName();
        } else {
            // only players the server has seen, by their current name (no lookup at Mojang, no player file read)
            OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (offline == null) {
                fail(player, "§cEinen Spieler §f" + args[0] + " §ckennt der Server nicht.");
                return true;
            }
            target = offline.getUniqueId();
            PlayerProfile profile = Bukkit.createProfile(target);
            profile.completeFromCache();
            name = profile.getName();
            if (name == null || !name.equalsIgnoreCase(args[0])) {
                fail(player, "§cEinen Spieler §f" + args[0] + " §ckennt der Server nicht.");
                return true;
            }
        }
        if (target.equals(player.getUniqueId())) {
            fail(player, "§cAuf dich selbst geht das nicht.");
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (!booking.add(uuid)) {
            fail(player, "§fEinen Moment, deine letzte Buchung läuft noch.");
            return true;
        }
        Tasks.async(() -> {
            Bounties.Pledge pledge;
            try {
                pledge = Bounties.pledge(uuid, target, amount);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Bounty of {} on {} failed: {}", uuid, target, e.toString());
                pledge = null;
            } finally {
                booking.remove(uuid);
            }
            Bounties.Pledge done = pledge;
            MainThread.run(() -> {
                if (done == null) {
                    if (player.isOnline()) fail(player, "§cDas ging gerade nicht, versuch es gleich nochmal.");
                    return;
                }
                switch (done.outcome()) {
                    case OK -> {
                        Logger.console("Bounty pledge by " + player.getName() + " on " + name + ": " + amount);
                        if (player.isOnline()) {
                            player.sendMessage(Main.getChatPrefix() + "§fDu hast §a" + Items.format(amount) + " Schilling §fauf §c" + name
                                    + " §fausgesetzt §7(+" + Bounties.PLEDGE_FEE + " Gebühr an den Staat)§f. Bleibt der Kopf aus, bekommst du es zurück.");
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
                        }
                        // without the sponsor's name
                        Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§c§lKOPFGELD §fJemand setzt §a" + Items.format(amount)
                                + " Schilling §fauf §c" + name + " §faus. §7(Von Spielern insgesamt: " + Items.format(done.total()) + ")"));
                    }
                    case NOT_WANTED -> fail(player, "§f" + name + " §cwird gerade nicht gesucht. §7(Kopfgeld nur auf Gesuchte, siehe /wanted)");
                    case INSUFFICIENT_FUNDS -> fail(player, "§cDafür reicht dein Konto nicht §7(" + Items.format(amount) + " + "
                            + Bounties.PLEDGE_FEE + " Gebühr)§c.");
                    case TOO_LOW -> fail(player, "§cDas ist kein gültiger Betrag.");
                    case UNKNOWN -> fail(player, "§cEinen Spieler §f" + name + " §ckennt der Server nicht.");
                }
            });
        });
        return true;
    }

    private static void fail(Player player, String message) {
        if (!player.isOnline()) return;
        player.sendMessage(Main.getChatPrefix() + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
    }

    // wanted players who are online and visible
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online == sender || (sender instanceof Player viewer && !viewer.canSee(online))) continue;
                if (!CacheHandler.getInstance().getPlayerInCache(online).isWanted()) continue;
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) options.add(online.getName());
            }
        } else if (args.length == 2) {
            options.addAll(List.of("500", "1.000", "5k"));
        }
        return options;
    }

}
