package xyz.jupp.minecraft.team;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.database.TeamRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The rules of teams (founding, levels, chunks, roles) and the changes the team menu makes.
 * The rules can be read from any thread; the changes block (database) and run on a worker.
 */
public final class Teams {

    private Teams() {}

    public static final int CREATION_COST = 2_500;
    // XP level the founder needs, it is not used up
    public static final int CREATION_LEVEL = 30;
    public static final int NAME_MIN = 3;
    public static final int NAME_MAX = 12;

    public static final int MAX_LEVEL = 5;
    public static final int CHUNK_COST = 200;
    private static final int[] CHUNK_LIMITS = {4, 9, 16, 25, 36};
    public static final int MOB_GRIEFING_LEVEL = 2;
    public static final int WARP_LEVEL = 2;
    public static final int PVP_LEVEL = 3;
    public static final int CHAT_LEVEL = 4;
    public static final int INTERACTION_LEVEL = 5;

    public enum Role {
        OWNER("Boss"), VICE("Vize"), MEMBER("Mitglied"), NONE("-");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Owner and vices run the team: invite, kick members, chunks, treasury payouts, warp, upgrades. */
        public boolean canManage() {
            return this == OWNER || this == VICE;
        }

        static Role of(@Nullable String stored) {
            if (stored == null) return NONE;
            return switch (stored) {
                case "owner" -> OWNER;
                case "vice" -> VICE;
                case "member" -> MEMBER;
                default -> NONE;
            };
        }
    }


    /* rules (any thread) */

    public static Role role(@Nullable TeamCacheObject team, @NotNull UUID player) {
        if (team == null) return Role.NONE;
        String id = player.toString();
        if (team.getTeamOwner().equals(id)) return Role.OWNER;
        if (team.getTeamVices().contains(id)) return Role.VICE;
        return team.getMembersList().stream().anyMatch(member -> member.uuid().equals(player)) ? Role.MEMBER : Role.NONE;
    }

    /** Team points for the next level. */
    public static int upgradeCost(int level) {
        return level <= 1 ? 5_000 : level * Main.getTeamLevelMultiple();
    }

    public static int chunkLimit(int level) {
        return CHUNK_LIMITS[Math.clamp(level, 1, MAX_LEVEL) - 1];
    }

    public static double xpMultiplier(int level) {
        return switch (level) {
            case 1 -> 1.10;
            case 2 -> 1.25;
            case 3 -> 1.35;
            case 4 -> 1.50;
            case 5 -> 2.0;
            default -> 1.0;
        };
    }

    /** What a level brings, for the level table in the menu. */
    public static List<String> benefits(int level) {
        List<String> lines = new ArrayList<>();
        lines.add("§7XP-Bonus: §f×" + String.valueOf(xpMultiplier(level)).replace('.', ','));
        lines.add("§7Chunks: §f" + chunkLimit(level));
        if (level == MOB_GRIEFING_LEVEL) lines.add("§7Neu: §fMob-Griefing abschaltbar");
        if (level == WARP_LEVEL) lines.add("§7Neu: §fTeam-Warp");
        if (level == PVP_LEVEL) lines.add("§7Neu: §fPvP im Gebiet abschaltbar");
        if (level == CHAT_LEVEL) lines.add("§7Neu: §fTeam-Chat mit §a@");
        if (level == INTERACTION_LEVEL) lines.add("§7Neu: §fGebiet für Fremde sperrbar");
        return lines;
    }

    /** Claimed chunks of the team (from the cache). */
    public static int claimedChunks(@NotNull String teamID) {
        return ChunkCache.getInstance().countClaims(teamID);
    }


    /* changes (worker thread) */

    public enum ClaimOutcome { OK, TAKEN, LIMIT, NO_POINTS, NOT_ALLOWED }

    /** Claims a chunk for the team: owner or vice, within the level's limit, for CHUNK_COST team points. */
    public static ClaimOutcome claim(@NotNull TeamCacheObject team, @NotNull UUID actor, @NotNull String world, int x, int z) {
        if (!role(team, actor).canManage()) return ClaimOutcome.NOT_ALLOWED;
        String teamID = team.getTeamID();
        int limit = chunkLimit(team.getLevel());
        ClaimOutcome outcome = Database.inTransaction(connection -> {
            // the team row is locked: two claims at once cannot both use the last free place
            Integer points = Database.queryOne(connection, "SELECT points FROM teams WHERE team_id = ? FOR UPDATE", row -> row.getInt(1), teamID);
            if (points == null) return ClaimOutcome.NOT_ALLOWED;
            Integer claimed = Database.queryOne(connection, "SELECT COUNT(*) FROM chunks WHERE team_id = ?", row -> row.getInt(1), teamID);
            if (claimed != null && claimed >= limit) return ClaimOutcome.LIMIT;
            if (points < CHUNK_COST) return ClaimOutcome.NO_POINTS;
            if (Database.update(connection, "INSERT INTO chunks (world, x, z, team_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    world, x, z, teamID) == 0) {
                return ClaimOutcome.TAKEN;
            }
            Database.update(connection, "UPDATE teams SET points = points - ? WHERE team_id = ?", CHUNK_COST, teamID);
            return ClaimOutcome.OK;
        });
        if (outcome == ClaimOutcome.OK) {
            ChunkCache.getInstance().claimed(teamID, world, x, z);
            Main.getInstance().getSLF4JLogger().info("claimed chunk {}:{}:{} for team {} (-{} team points)", world, x, z, teamID, CHUNK_COST);
        }
        return outcome;
    }

    /** Releases a chunk of the team (owner or vice); the points are not given back. */
    public static boolean release(@NotNull TeamCacheObject team, @NotNull UUID actor, @NotNull String world, int x, int z) {
        if (!role(team, actor).canManage()) return false;
        return ChunkCache.getInstance().removeChunk(team.getTeamID(), world, x, z);
    }

    public enum UpgradeOutcome { OK, MAX, NO_POINTS, NOT_ALLOWED }

    public static UpgradeOutcome upgrade(@NotNull TeamCacheObject team, @NotNull UUID actor) {
        if (!role(team, actor).canManage()) return UpgradeOutcome.NOT_ALLOWED;
        // level, price and withdrawal under the team's lock and in one statement: a double click buys one level
        synchronized (team) {
            int level = team.getLevel();
            if (level >= MAX_LEVEL) return UpgradeOutcome.MAX;
            return team.upgrade(upgradeCost(level)) ? UpgradeOutcome.OK : UpgradeOutcome.NO_POINTS;
        }
    }

    /** Owner only: member <-> vice. The new role, null if not allowed. */
    public static @Nullable Role toggleVice(@NotNull TeamCacheObject team, @NotNull UUID actor, @NotNull UUID target) {
        if (role(team, actor) != Role.OWNER) return null;
        Role current = role(team, target);
        if (current != Role.MEMBER && current != Role.VICE) return null;
        String stored = team.changePlayerTeamRole(target);
        return stored == null ? null : Role.of(stored);
    }

    /** Whether the actor may remove the target: the owner everyone else, a vice only plain members. */
    public static boolean mayKick(@NotNull TeamCacheObject team, @NotNull UUID actor, @NotNull UUID target) {
        if (actor.equals(target)) return false;
        Role actorRole = role(team, actor);
        Role targetRole = role(team, target);
        if (targetRole == Role.NONE || targetRole == Role.OWNER) return false;
        return actorRole == Role.OWNER || (actorRole == Role.VICE && targetRole == Role.MEMBER);
    }

    /** Removes a member, online or offline. false if not allowed. */
    public static boolean kick(@NotNull TeamCacheObject team, @NotNull UUID actor, @NotNull UUID target) {
        if (!mayKick(team, actor, target)) return false;
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            CacheHandler.getInstance().removePlayerFromTeam(online, team);
        } else {
            team.removeMember(target);
            TeamRepository.clearPlayerTeam(target, team.getTeamID());
            CacheHandler.getInstance().clearTeamIfCached(target, team.getTeamID());
        }
        Main.getInstance().getSLF4JLogger().info("{} removed {} from team {}", actor, target, team.getTeamID());
        return true;
    }

    /** Case-insensitive: is the name used by another team already? */
    public static boolean nameTaken(@NotNull String name) {
        return Database.queryOne("SELECT 1 FROM teams WHERE lower(name) = lower(?)", row -> 1, name) != null;
    }

    /** Main thread: a message to every online member. */
    public static void notifyTeam(@NotNull String teamID, @NotNull String message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (teamID.equals(CacheHandler.getInstance().getPlayerInCache(online).getTeamID())) online.sendMessage(message);
        }
    }

}
