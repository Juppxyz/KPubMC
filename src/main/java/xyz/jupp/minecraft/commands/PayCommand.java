package xyz.jupp.minecraft.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import com.destroystokyo.paper.profile.PlayerProfile;
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
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.listener.CreateLocalShopListener;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /zahlen <Spieler> <Betrag>: pays another player straight from the account, also one who is offline (any player the
 * server has seen). Untaxed like /geld. From CONFIRM_FROM on the payment waits for a click on "Bestätigen".
 */
public class PayCommand implements CommandExecutor, TabCompleter {

    public static final int CONFIRM_FROM = 10_000;
    private static final long CONFIRM_MILLIS = 60_000;
    private static final String CONFIRM = "bestätigen";

    // token: what the button carries, so an old button cannot confirm a newer offer
    private record Pending(UUID target, String name, int amount, long until, String token) {}

    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    // one payment per player at a time
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
        UUID uuid = player.getUniqueId();
        if (args.length >= 1 && (CONFIRM.equalsIgnoreCase(args[0]) || "bestaetigen".equalsIgnoreCase(args[0]))) {
            Pending open = pending.get(uuid);
            if (open == null || open.until() < System.currentTimeMillis()) {
                pending.remove(uuid);
                fail(player, "§fEs wartet keine Überweisung auf deine Bestätigung.");
                return true;
            }
            if (args.length < 2) {
                fail(player, "§fBitte auf §a[Bestätigen] §fin der Nachricht klicken.");
                return true;
            }
            if (!open.token().equals(args[1])) {
                fail(player, "§fDieses Angebot gilt nicht mehr, es wurde durch ein neueres ersetzt.");
                return true;
            }
            if (booking.contains(uuid)) {
                fail(player, "§fEinen Moment, deine letzte Überweisung läuft noch.");
                return true;
            }
            pending.remove(uuid);
            book(player, open.target(), open.name(), open.amount());
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(Main.getChatPrefix() + "§f/" + label + " <Spieler> <Betrag> §7(z. B. /" + label + " Steve 1.500 oder 2,5k)");
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
            // only players the server has seen: no lookup at Mojang and no player file read on the main thread (a uuid
            // without an account is refused by the booking itself)
            OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (offline == null) {
                fail(player, "§cEinen Spieler §f" + args[0] + " §ckennt der Server nicht.");
                return true;
            }
            target = offline.getUniqueId();
            // the name cache keeps an old name after a rename: the uuid's current name decides
            PlayerProfile profile = Bukkit.createProfile(target);
            profile.completeFromCache();
            name = profile.getName();
            if (name == null || !name.equalsIgnoreCase(args[0])) {
                fail(player, "§cEinen Spieler §f" + args[0] + " §ckennt der Server nicht" + (name == null ? "." : " §7(der hieß so, heißt jetzt " + name + ")§c."));
                return true;
            }
        }
        if (target.equals(uuid)) {
            fail(player, "§cAn dich selbst geht das nicht.");
            return true;
        }
        if (amount >= CONFIRM_FROM) {
            String token = Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(0x100000, 0xFFFFFF));
            Pending replaced = pending.put(uuid, new Pending(target, name, amount, System.currentTimeMillis() + CONFIRM_MILLIS, token));
            if (replaced != null && replaced.until() >= System.currentTimeMillis()) {
                player.sendMessage(Main.getChatPrefix() + "§7(Ersetzt die offene Überweisung an " + replaced.name() + ".)");
            }
            player.sendMessage(Text.section(Main.getChatPrefix() + "§f" + Items.format(amount) + " Schilling an §a" + name + "§f? ")
                    .append(Component.text("[Bestätigen]", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/zahlen " + CONFIRM + " " + token))
                            .hoverEvent(Component.text("Überweisung ausführen", NamedTextColor.GRAY)))
                    .append(Text.section(" §7(" + CONFIRM_MILLIS / 1000 + " Sekunden gültig)")));
            return true;
        }
        book(player, target, name, amount);
        return true;
    }

    // the sender's account first, the receiver only on success (one transaction, PlayerRepository.transferMoney)
    private static void book(Player player, UUID target, String name, int amount) {
        UUID uuid = player.getUniqueId();
        if (!booking.add(uuid)) {
            fail(player, "§fEinen Moment, deine letzte Überweisung läuft noch.");
            return;
        }
        Tasks.async(() -> {
            PlayerRepository.TransferResult result;
            try {
                result = PlayerRepository.transferMoney(uuid, target, amount);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Payment of {} to {} failed: {}", uuid, target, e.toString());
                result = null;
            } finally {
                booking.remove(uuid);
            }
            PlayerRepository.TransferResult done = result;
            MainThread.run(() -> {
                if (done == PlayerRepository.TransferResult.SUCCESS) {
                    Logger.console("Payment from " + player.getName() + " to " + name + ": " + amount);
                    if (player.isOnline()) {
                        player.sendMessage(Main.getChatPrefix() + "§fDu hast §a" + Items.format(amount) + " Schilling §fan §a" + name + " §füberwiesen.");
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 2f);
                    }
                    Player receiver = Bukkit.getPlayer(target);
                    if (receiver != null) {
                        receiver.sendMessage(Main.getChatPrefix() + "§a" + player.getName() + " §fhat dir §a" + Items.format(amount) + " Schilling §füberwiesen.");
                        receiver.playSound(receiver.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 2f);
                    }
                    return;
                }
                if (!player.isOnline()) return;
                if (done == PlayerRepository.TransferResult.INSUFFICIENT_FUNDS) fail(player, "§cSo viel hast du nicht auf dem Konto.");
                else if (done == PlayerRepository.TransferResult.TARGET_NOT_FOUND) fail(player, "§cDen Spieler gibt es auf dem Server nicht mehr.");
                else fail(player, "§cDas ging gerade nicht, versuch es gleich nochmal.");
            });
        });
    }

    private static void fail(Player player, String message) {
        player.sendMessage(Main.getChatPrefix() + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                // admins hidden with /spec stay hidden
                if (online == sender || (sender instanceof Player viewer && !viewer.canSee(online))) continue;
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) options.add(online.getName());
            }
        } else if (args.length == 2) {
            options.addAll(List.of("100", "1.000", "10k"));
        }
        return options;
    }

}
