package xyz.jupp.minecraft.jail;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Sheriff (/createsheriff): a right-click hands him every bounty head in the inventory, he pays what they are
 * worth (Bounties). He does nothing else. Only used on the main thread.
 */
public final class Sheriff {

    private Sheriff() {}

    public static final String PREFIX = "§9Sheriff §8» §f";

    // players whose heads are being paid right now: a second click waits for the first
    private static final Set<UUID> busy = new HashSet<>();

    public static void interact(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (busy.contains(uuid)) return;
        String team = CacheHandler.getInstance().getPlayerInCache(player).getTeamID();
        List<Bounties.Head> heads = new ArrayList<>();
        boolean refused = false;
        for (ItemStack item : player.getInventory().getContents()) {
            Bounties.Head head = Bounties.read(item);
            if (head == null) continue;
            // the own head and those of the own team pay nothing
            if (head.wanted().equals(uuid) || (team != null && team.equals(head.wantedTeam()))) {
                refused = true;
            } else if (heads.stream().noneMatch(other -> other.id().equals(head.id()))) {
                heads.add(head);
            }
        }
        if (heads.isEmpty()) {
            player.sendMessage(PREFIX + (refused
                    ? "Den Kopf nehme ich von dir nicht an. §7(Für dich selbst und dein Team gibt es kein Kopfgeld.)"
                    : "Bring mir den Kopf eines Gesuchten, dann zahle ich das Kopfgeld. §7(Wer gesucht wird: §a/wanted§7)"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        busy.add(uuid);
        Tasks.async(() -> {
            List<Bounties.Claim> claims = new ArrayList<>();
            boolean failed = false;
            try {
                for (Bounties.Head head : heads) claims.add(Bounties.claim(head.id(), uuid, team));
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Bounty heads of {} could not be paid: {}", player.getName(), e.toString());
                failed = true;
            }
            boolean interrupted = failed;
            MainThread.run(() -> {
                busy.remove(uuid);
                finish(player, team, claims, interrupted);
            });
        });
    }

    private static void finish(Player player, @Nullable String team, List<Bounties.Claim> claims, boolean failed) {
        // paid now or before: the head is worth nothing any more
        Set<UUID> spent = new HashSet<>();
        for (Bounties.Claim claim : claims) {
            if (claim.outcome() == Bounties.Outcome.PAID || claim.outcome() == Bounties.Outcome.ALREADY_PAID) spent.add(claim.id());
        }
        if (!player.isOnline()) return;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            Bounties.Head head = Bounties.read(inventory.getItem(slot));
            if (head != null && spent.contains(head.id())) inventory.setItem(slot, null);
        }
        boolean paid = false;
        for (Bounties.Claim claim : claims) {
            switch (claim.outcome()) {
                case PAID -> {
                    paid = true;
                    player.sendMessage(PREFIX + "Gute Arbeit. Für den Kopf von §c" + claim.wantedName() + "§f: §a+" + Items.format(claim.amount())
                            + " Schilling" + (claim.points() > 0 ? " §fund §a+" + claim.points() + " Team-Punkte" : ""));
                    Bukkit.broadcast(Text.section(PREFIX + player.getName() + " §fhat das Kopfgeld für §c" + claim.wantedName() + " §fkassiert."));
                    if (team != null && claim.points() > 0) notifyTeam(team, player, "§a+" + claim.points() + " Team-Punkte §ffür das Kopfgeld von §c"
                            + claim.wantedName() + " §7(abgegeben von " + player.getName() + ")");
                }
                case ALREADY_PAID -> player.sendMessage(PREFIX + "Für den Kopf von §c" + claim.wantedName() + " §fhabe ich schon bezahlt.");
                case UNKNOWN -> player.sendMessage(PREFIX + "Diesen Kopf kenne ich nicht.");
                case TREASURY_EMPTY -> {
                    if (claim.amount() > 0) {
                        paid = true;
                        player.sendMessage(PREFIX + "Das Kopfgeld der Spieler für §c" + claim.wantedName() + "§f: §a+" + Items.format(claim.amount()) + " Schilling§f.");
                    }
                    player.sendMessage(PREFIX + "Der Staat schuldet dir für §c" + claim.wantedName() + " §fnoch §a" + Items.format(claim.pending())
                            + " Schilling§f, aber die Staatskasse hat das gerade nicht. §7Behalt den Kopf, bring ihn später wieder.");
                }
            }
        }
        if (failed) player.sendMessage(PREFIX + "Gerade geht nichts, versuch es gleich nochmal.");
        player.playSound(player.getLocation(), paid ? Sound.ENTITY_VILLAGER_YES : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        if (paid) player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    // the other online members: the player got the points in their own message
    private static void notifyTeam(String team, Player player, String message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online != player && team.equals(CacheHandler.getInstance().getPlayerInCache(online).getTeamID())) {
                online.sendMessage(Main.getChatPrefix() + message);
            }
        }
    }

}
