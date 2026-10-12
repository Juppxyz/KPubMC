package xyz.jupp.minecraft.team;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Team invitations: boss and vices invite a player without a team from the team menu, the player accepts or declines
 * in the chat (clickable, or /team annehmen|ablehnen <Team>; /einladungen lists them). Only in memory: an invitation
 * is valid for a few minutes and a restart drops it. After a decline or a withdrawal the same team has to wait before
 * inviting that player again, so nobody can be flooded with invitations.
 */
public final class TeamInvites {

    private TeamInvites() {}

    public static final Duration VALID = Duration.ofMinutes(5);
    public static final Duration DECLINE_COOLDOWN = Duration.ofMinutes(5);
    public static final Duration WITHDRAW_COOLDOWN = Duration.ofMinutes(2);

    public record Invite(String teamID, UUID target, UUID inviter, String inviterName, Instant expires) {}

    /** The team has to wait until {@code until}: the player declined, or the team withdrew its invitation. */
    public record Cooldown(Instant until, boolean declined) {}

    public enum SendOutcome { OK, PENDING, COOLDOWN }

    // per invited player: team id -> invitation; the menu sends from a worker, the commands run on the main thread
    private static final Map<UUID, Map<String, Invite>> INVITES = new HashMap<>();
    // per team and player
    private static final Map<String, Cooldown> COOLDOWNS = new HashMap<>();


    /* state (any thread) */

    public static synchronized SendOutcome send(@NotNull String teamID, @NotNull UUID target, @NotNull UUID inviter, @NotNull String inviterName) {
        Instant now = Instant.now();
        prune(now);
        if (COOLDOWNS.containsKey(key(teamID, target))) return SendOutcome.COOLDOWN;
        Map<String, Invite> invites = INVITES.computeIfAbsent(target, uuid -> new HashMap<>());
        if (invites.containsKey(teamID)) return SendOutcome.PENDING;
        invites.put(teamID, new Invite(teamID, target, inviter, inviterName, now.plus(VALID)));
        return SendOutcome.OK;
    }

    /** The open invitation of the team for the player, null if there is none (any more). */
    public static synchronized @Nullable Invite pending(@NotNull String teamID, @NotNull UUID target) {
        prune(Instant.now());
        Map<String, Invite> invites = INVITES.get(target);
        return invites == null ? null : invites.get(teamID);
    }

    /** Whether and until when the team has to wait before inviting the player again, null if it may invite. */
    public static synchronized @Nullable Cooldown cooldown(@NotNull String teamID, @NotNull UUID target) {
        prune(Instant.now());
        return COOLDOWNS.get(key(teamID, target));
    }

    /** The player's open invitations, the oldest first. */
    public static synchronized List<Invite> of(@NotNull UUID target) {
        prune(Instant.now());
        Map<String, Invite> invites = INVITES.get(target);
        if (invites == null) return List.of();
        List<Invite> list = new ArrayList<>(invites.values());
        list.sort(Comparator.comparing(Invite::expires));
        return list;
    }

    // takes the invitation away from the player, null if there is none (any more)
    private static synchronized @Nullable Invite remove(@NotNull String teamID, @NotNull UUID target) {
        prune(Instant.now());
        Map<String, Invite> invites = INVITES.get(target);
        Invite invite = invites == null ? null : invites.remove(teamID);
        if (invites != null && invites.isEmpty()) INVITES.remove(target);
        return invite;
    }

    /** The team takes its invitation back, null if there is none (any more). */
    public static synchronized @Nullable Invite withdraw(@NotNull String teamID, @NotNull UUID target) {
        Invite invite = remove(teamID, target);
        if (invite != null) COOLDOWNS.put(key(teamID, target), new Cooldown(Instant.now().plus(WITHDRAW_COOLDOWN), false));
        return invite;
    }

    /** A dissolved team: its invitations are void. */
    public static synchronized void forgetTeam(@NotNull String teamID) {
        for (Iterator<Map<String, Invite>> perPlayer = INVITES.values().iterator(); perPlayer.hasNext(); ) {
            Map<String, Invite> invites = perPlayer.next();
            invites.remove(teamID);
            if (invites.isEmpty()) perPlayer.remove();
        }
        COOLDOWNS.keySet().removeIf(key -> key.startsWith(teamID + ":"));
    }

    // a join that failed for a technical reason: the player may click again while the invitation is still valid
    private static synchronized void restore(@NotNull Invite invite) {
        if (invite.expires().isAfter(Instant.now())) {
            INVITES.computeIfAbsent(invite.target(), uuid -> new HashMap<>()).putIfAbsent(invite.teamID(), invite);
        }
    }

    private static synchronized @Nullable Invite declineInvite(@NotNull String teamID, @NotNull UUID target) {
        Invite invite = remove(teamID, target);
        if (invite != null) COOLDOWNS.put(key(teamID, target), new Cooldown(Instant.now().plus(DECLINE_COOLDOWN), true));
        return invite;
    }

    /** The player is in a team now: every other invitation is void. */
    public static synchronized void clear(@NotNull UUID target) {
        INVITES.remove(target);
    }

    private static void prune(Instant now) {
        for (Iterator<Map<String, Invite>> perPlayer = INVITES.values().iterator(); perPlayer.hasNext(); ) {
            Map<String, Invite> invites = perPlayer.next();
            invites.values().removeIf(invite -> !invite.expires().isAfter(now));
            if (invites.isEmpty()) perPlayer.remove();
        }
        COOLDOWNS.values().removeIf(cooldown -> !cooldown.until().isAfter(now));
    }

    private static String key(String teamID, UUID target) {
        return teamID + ":" + target;
    }

    /** "4 Min", at least 1 Min. */
    public static String minutesLeft(@NotNull Instant until) {
        long seconds = Duration.between(Instant.now(), until).getSeconds();
        return Math.max(1, (seconds + 59) / 60) + " Min";
    }


    /* chat (main thread) */

    /** The invitation in the chat of the invited player, with the buttons. */
    public static void announce(@NotNull Player target, @NotNull TeamCacheObject team, @NotNull String inviterName) {
        if (!target.isOnline()) return;
        target.sendMessage(Main.getChatPrefix() + "§6" + inviterName + " §flädt dich ins Team " + team.getTeamColor() + team.getTeamName() + " §fein.");
        target.sendMessage(buttons(team, "§7(gilt " + VALID.toMinutes() + " Minuten)"));
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    /** /einladungen: the open invitations with their buttons. */
    public static void list(@NotNull Player player) {
        if (CacheHandler.getInstance().getPlayerInCache(player).getTeamID() != null) {
            fail(player, "Du bist schon in einem Team, Einladungen gibt es nur ohne Team.");
            return;
        }
        Map<Invite, TeamCacheObject> invites = new LinkedHashMap<>();
        for (Invite invite : of(player.getUniqueId())) {
            TeamCacheObject team = CacheHandler.getInstance().getTeamCacheObject(invite.teamID());
            if (team != null) invites.put(invite, team);
        }
        if (invites.isEmpty()) {
            player.sendMessage(Main.getChatPrefix() + "Du hast gerade keine offenen Einladungen. §7Boss und Vize eines Teams laden dich über ihr Team-Menü ein.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }
        player.sendMessage(Main.getChatPrefix() + "Deine offenen Einladungen§8:");
        invites.forEach((invite, team) -> {
            player.sendMessage("§8§l» " + team.getTeamColor() + team.getTeamName() + " §7von " + invite.inviterName()
                    + ", noch " + minutesLeft(invite.expires()));
            player.sendMessage(buttons(team, ""));
        });
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    /** /team annehmen <Team> */
    public static void accept(@NotNull Player player, @NotNull String teamName) {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        if (pco.getTeamID() != null) {
            fail(player, "Du bist schon in einem Team.");
            return;
        }
        TeamCacheObject team = byName(teamName);
        Invite invite = team == null ? null : remove(team.getTeamID(), player.getUniqueId());
        if (invite == null) {
            fail(player, "Von diesem Team hast du keine offene Einladung §7(abgelaufen oder zurückgezogen)§f.");
            return;
        }
        Tasks.async(() -> {
            Boolean joined;
            try {
                joined = CacheHandler.getInstance().addPlayerToTeam(player, team);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("{} could not join team {}: {}", player.getName(), team.getTeamID(), e.toString());
                restore(invite);
                joined = null;
            }
            Boolean result = joined;
            MainThread.run(() -> {
                if (result == null) {
                    // e.g. the team was dissolved in the meantime: then the invitation is void, not worth another click
                    boolean gone = CacheHandler.getInstance().getTeamCacheObject(team.getTeamID()) == null;
                    if (gone) remove(team.getTeamID(), player.getUniqueId());
                    if (player.isOnline()) fail(player, gone ? "Das Team gibt es nicht mehr." : "Das ging gerade nicht, versuch es gleich nochmal.");
                    return;
                }
                if (!result) {
                    if (player.isOnline()) fail(player, "Du bist schon in einem Team.");
                    return;
                }
                clear(player.getUniqueId());
                Teams.notifyTeam(team.getTeamID(), teamInfo(team) + "§a" + player.getName() + " §fist jetzt in unserem Team!");
                if (!player.isOnline()) return;
                player.sendMessage(Main.getChatPrefix() + "Du bist jetzt im Team " + team.getTeamColor() + team.getTeamName() + "§f. §7Alles Weitere mit §a/team§7.");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                JailHandler.refreshPlayerName(player, pco);
            });
        });
    }

    /** /team ablehnen <Team> */
    public static void decline(@NotNull Player player, @NotNull String teamName) {
        TeamCacheObject team = byName(teamName);
        Invite invite = team == null ? null : declineInvite(team.getTeamID(), player.getUniqueId());
        if (invite == null) {
            fail(player, "Von diesem Team hast du keine offene Einladung §7(abgelaufen oder zurückgezogen)§f.");
            return;
        }
        player.sendMessage(Main.getChatPrefix() + "Du hast die Einladung von " + team.getTeamColor() + team.getTeamName() + " §fabgelehnt.");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        Player inviter = Bukkit.getPlayer(invite.inviter());
        if (inviter != null) {
            inviter.sendMessage(teamInfo(team) + "§f" + player.getName() + " §chat eure Einladung abgelehnt.");
            inviter.playSound(inviter.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        }
    }

    private static Component buttons(TeamCacheObject team, String note) {
        String name = team.getTeamName();
        Component accept = Component.text("[✔ Annehmen]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/team annehmen " + name))
                .hoverEvent(HoverEvent.showText(Text.section("§7Dem Team " + team.getTeamColor() + name + " §7beitreten")));
        Component decline = Component.text("[✖ Ablehnen]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/team ablehnen " + name))
                .hoverEvent(HoverEvent.showText(Text.section("§7Die Einladung von " + team.getTeamColor() + name + " §7ablehnen")));
        return Component.text()
                .append(Text.section(Main.getChatPrefix()))
                .append(accept)
                .append(Component.text("  "))
                .append(decline)
                .append(Text.section(note.isEmpty() ? "" : " " + note))
                .build();
    }

    private static @Nullable TeamCacheObject byName(String name) {
        for (TeamCacheObject team : CacheHandler.getInstance().getAllTeams()) {
            if (team.getTeamName().equalsIgnoreCase(name)) return team;
        }
        return null;
    }

    private static String teamInfo(TeamCacheObject team) {
        return team.getTeamColor() + "Team-Info §8» ";
    }

    private static void fail(Player player, String message) {
        player.sendMessage(Main.getChatPrefix() + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
    }
}
