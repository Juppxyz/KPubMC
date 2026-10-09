package xyz.jupp.minecraft.team;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.economy.TeamBank;
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

    public static final int MAX_LEVEL = 7;
    public static final int CHUNK_COST = 200;
    private static final int[] CHUNK_LIMITS = {4, 9, 16, 25, 36, 49, 64};
    public static final int MOB_GRIEFING_LEVEL = 2;
    public static final int WARP_LEVEL = 2;
    public static final int PVP_LEVEL = 3;
    public static final int ALARM_LEVEL = 3;
    public static final int CHAT_LEVEL = 4;
    public static final int HASTE_LEVEL = 4;
    public static final int INTERACTION_LEVEL = 5;
    public static final int SECOND_WARP_LEVEL = 6;
    public static final int STRONG_HASTE_LEVEL = 7;
    // a member killed by a player costs the team this many points (the killer's team gets them)
    public static final int DEATH_COST = 500;

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
            case 5, 6, 7 -> 2.0;
            default -> 1.0;
        };
    }

    /** The haste the team's members get in their own area: -1 none, 0 Eile I, 1 Eile II. */
    public static int hasteAmplifier(int level) {
        return level >= STRONG_HASTE_LEVEL ? 1 : level >= HASTE_LEVEL ? 0 : -1;
    }

    /** What a level brings, for the level table in the menu. */
    public static List<String> benefits(int level) {
        List<String> lines = new ArrayList<>();
        lines.add("§7XP-Bonus: §f×" + String.valueOf(xpMultiplier(level)).replace('.', ','));
        lines.add("§7Chunks: §f" + chunkLimit(level));
        if (level == MOB_GRIEFING_LEVEL) lines.add("§7Neu: §fMob-Griefing abschaltbar");
        if (level == WARP_LEVEL) lines.add("§7Neu: §fTeam-Warp");
        if (level == PVP_LEVEL) lines.add("§7Neu: §fPvP im Gebiet abschaltbar");
        if (level == ALARM_LEVEL) lines.add("§7Neu: §fAlarm, wenn Fremde euer Gebiet betreten");
        if (level == CHAT_LEVEL) lines.add("§7Neu: §fTeam-Chat mit §a@");
        if (level == HASTE_LEVEL) lines.add("§7Neu: §fEile I im eigenen Gebiet");
        if (level == INTERACTION_LEVEL) lines.add("§7Neu: §fGebiet für Fremde sperrbar");
        if (level == SECOND_WARP_LEVEL) lines.add("§7Neu: §fzweiter Team-Warp");
        if (level == STRONG_HASTE_LEVEL) lines.add("§7Neu: §fEile II im eigenen Gebiet");
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

    /** What a death cost the team: the points taken (the killer's team got them), levels sold back and their points. */
    public record Penalty(int taken, int levelsLost, int refunded, int newLevel) {}

    /**
     * A member was killed by a player: the team pays DEATH_COST points. If they are not enough, it drops a level and
     * gets that level's price back to pay with (never below level 1; there it pays what it has). The killer's team
     * (null inside the own team) gets what was paid, in the same transaction. Null if the team does not exist.
     * Example: level 4 with 200 points -> level 3 with 200 - 500 + 15.000 = 14.700 points.
     */
    public static @Nullable Penalty deathPenalty(@NotNull TeamCacheObject team, @Nullable String killerTeamID) {
        String teamID = team.getTeamID();
        // same monitor as upgrade(): the cached level follows the database
        synchronized (team) {
            Penalty penalty = Database.inTransaction(connection -> {
                // both rows in a fixed order: two teams killing each other at the same moment never deadlock
                List<String> locked = killerTeamID == null ? List.of(teamID)
                        : teamID.compareTo(killerTeamID) < 0 ? List.of(teamID, killerTeamID) : List.of(killerTeamID, teamID);
                for (String id : locked) Database.queryOne(connection, "SELECT 1 FROM teams WHERE team_id = ? FOR UPDATE", row -> 1, id);
                int[] row = Database.queryOne(connection, "SELECT points, level FROM teams WHERE team_id = ?",
                        result -> new int[]{result.getInt(1), result.getInt(2)}, teamID);
                if (row == null) return null;
                int points = row[0] - DEATH_COST;
                int level = row[1];
                int refunded = 0;
                int lost = 0;
                while (points < 0 && level > 1) {
                    level--;
                    int back = upgradeCost(level);
                    points += back;
                    refunded += back;
                    lost++;
                }
                int taken = DEATH_COST + Math.min(0, points);
                points = Math.max(0, points);
                // options the lower level does not have any more go back to on
                Database.update(connection, "UPDATE teams SET points = ?, level = ?, zone_mob_damage = zone_mob_damage OR ?, "
                                + "zone_pvp = zone_pvp OR ?, zone_interact = zone_interact OR ? WHERE team_id = ?",
                        points, level, level < MOB_GRIEFING_LEVEL, level < PVP_LEVEL, level < INTERACTION_LEVEL, teamID);
                if (killerTeamID != null && taken > 0) {
                    Database.update(connection, "UPDATE teams SET points = points + ? WHERE team_id = ?", taken, killerTeamID);
                }
                return new Penalty(taken, lost, refunded, level);
            });
            if (penalty != null && penalty.levelsLost() > 0) {
                int level = penalty.newLevel();
                team.levelDropped(level, level < MOB_GRIEFING_LEVEL, level < PVP_LEVEL, level < INTERACTION_LEVEL);
            }
            if (penalty != null) {
                Main.getInstance().getSLF4JLogger().info("team {} paid {} for a death{}", teamID, penalty.taken(),
                        penalty.levelsLost() > 0 ? ", dropped to level " + penalty.newLevel() + " (+" + penalty.refunded() + ")" : "");
            }
            return penalty;
        }
    }

    /** What happened when the boss left: the new boss, or a dissolved team with its treasury payout. */
    public record Handover(@Nullable UUID newOwner, @Nullable String newOwnerName, boolean dissolved, long paid, long tax) {}

    // who takes over when the boss leaves: the vice longest in the team, without vices the member longest in it;
    // on the same day the one with more Nomad points, then by name
    static final String SUCCESSOR_SQL = """
            SELECT m.uuid, m.nickname FROM team_members m
            LEFT JOIN (SELECT player_uuid, SUM(points) AS points FROM nomad_deliveries WHERE team_id = ? GROUP BY player_uuid) d
                ON d.player_uuid = m.uuid
            WHERE m.team_id = ? AND m.role <> 'owner'
            ORDER BY (m.role = 'vice') DESC, date_trunc('day', m.joined_at), COALESCE(d.points, 0) DESC, lower(m.nickname)
            LIMIT 1""";

    /**
     * Worker: the boss leaves. The next vice takes over (see SUCCESSOR_SQL). A boss alone dissolves the team: chunks,
     * warps and relations go with it, the team treasury is paid out to them with the tax of a cash withdrawal.
     * Null if the player is not the boss.
     */
    public static @Nullable Handover ownerLeaves(@NotNull TeamCacheObject team, @NotNull UUID owner) {
        if (role(team, owner) != Role.OWNER) return null;
        String teamID = team.getTeamID();
        synchronized (team) {
            Handover handover = Database.inTransaction(connection -> {
                if (Database.queryOne(connection, "SELECT 1 FROM teams WHERE team_id = ? AND owner_uuid = ? FOR UPDATE", row -> 1, teamID, owner) == null) {
                    return null;
                }
                UUID[] next = new UUID[1];
                String nextName = Database.queryOne(connection, SUCCESSOR_SQL, row -> {
                    next[0] = row.getObject(1, UUID.class);
                    return row.getString(2);
                }, teamID, teamID);
                if (next[0] != null) {
                    Database.update(connection, "UPDATE teams SET owner_uuid = ? WHERE team_id = ?", next[0], teamID);
                    Database.update(connection, "UPDATE team_members SET role = 'owner' WHERE team_id = ? AND uuid = ?", teamID, next[0]);
                    Database.update(connection, "DELETE FROM team_members WHERE team_id = ? AND uuid = ?", teamID, owner);
                    Database.update(connection, "UPDATE players SET team_id = NULL WHERE uuid = ? AND team_id = ?", owner, teamID);
                    return new Handover(next[0], nextName, false, 0, 0);
                }
                TeamBank.Payout payout = TeamBank.payOutAll(connection, teamID, owner);
                // chunks, members, warps, relations, requests and the ledger go with the team (ON DELETE CASCADE)
                Database.update(connection, "DELETE FROM teams WHERE team_id = ?", teamID);
                Database.update(connection, "UPDATE players SET team_id = NULL WHERE uuid = ? AND team_id = ?", owner, teamID);
                return new Handover(null, null, true, payout.paid(), payout.tax());
            });
            if (handover == null) return null;
            if (handover.dissolved()) {
                TeamBank.committed(handover.tax());
                ChunkCache.getInstance().forgetTeam(teamID);
                TeamWarps.forgetTeam(teamID);
                Relations.forgetTeam(teamID);
                CacheHandler.getInstance().forgetTeam(teamID);
                Main.getInstance().getSLF4JLogger().info("team {} dissolved by {} (+{} from the treasury)", teamID, owner, handover.paid());
            } else {
                team.ownerChanged(handover.newOwner(), owner);
                Main.getInstance().getSLF4JLogger().info("team {}: {} left, {} is the new owner", teamID, owner, handover.newOwner());
            }
            CacheHandler.getInstance().clearTeamIfCached(owner, teamID);
            return handover;
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
