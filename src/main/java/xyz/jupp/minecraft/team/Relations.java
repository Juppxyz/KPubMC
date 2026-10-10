package xyz.jupp.minecraft.team;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Text;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Partnerships and wars between teams (table team_relations, one row per pair with team_a < team_b) and the open
 * requests (team_requests: partnership requests and peace offers).
 * <p>
 * Partners share their areas, never fight each other and may use each other's team warp (both from WARP_LEVEL on);
 * both teams have to agree, one is enough to end it. A war is declared by one team alone: PvP between the two is on
 * everywhere, also in protected areas, kills move team points as always. It ends with a peace both accept or after
 * WAR_QUIET without a kill between them.
 * <p>
 * The state lives in memory (the event checks run on the main thread), loaded in onEnable; changes are written
 * through (blocking, on a worker) under one lock.
 */
public final class Relations {

    private Relations() {}

    public static final Duration WAR_QUIET = Duration.ofDays(3);
    public static final Duration REQUEST_LIFETIME = Duration.ofDays(7);

    public enum Kind { PARTNER, WAR }

    public enum RequestKind { PARTNER, PEACE }

    public record Relation(String teamA, String teamB, Kind kind, @Nullable String declaredBy, Instant since, Instant lastKill) {
        public String other(@NotNull String team) {
            return team.equals(teamA) ? teamB : teamA;
        }

        /** When the war ends if nobody is killed before. */
        public Instant quietEnd() {
            return lastKill.plus(WAR_QUIET);
        }
    }

    public record Request(String from, String to, RequestKind kind, Instant at) {}

    public enum Result { REQUESTED, PARTNERED, ENDED, WAR, PEACE_OFFERED, PEACE, WITHDRAWN, DECLINED, NOT_POSSIBLE }

    private record Pair(String a, String b) {
        static Pair of(String x, String y) {
            return x.compareTo(y) < 0 ? new Pair(x, y) : new Pair(y, x);
        }
    }

    private record RequestKey(String from, String to, RequestKind kind) {}

    private static final ConcurrentHashMap<Pair, Relation> relations = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RequestKey, Request> requests = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();


    /** Blocking, in onEnable after the teams. */
    public static int load() {
        relations.clear();
        requests.clear();
        Database.query("SELECT team_a, team_b, kind, declared_by, since, last_kill FROM team_relations", row -> {
            Relation relation = new Relation(row.getString(1), row.getString(2), Kind.valueOf(row.getString(3)), row.getString(4),
                    row.getTimestamp(5).toInstant(), row.getTimestamp(6).toInstant());
            relations.put(new Pair(relation.teamA(), relation.teamB()), relation);
            return null;
        });
        Database.query("SELECT from_team, to_team, kind, created_at FROM team_requests", row -> {
            Request request = new Request(row.getString(1), row.getString(2), RequestKind.valueOf(row.getString(3)), row.getTimestamp(4).toInstant());
            requests.put(new RequestKey(request.from(), request.to(), request.kind()), request);
            return null;
        });
        return relations.size();
    }

    /** Every 5 minutes on a worker: quiet wars end, old requests go; the end of a war is announced. */
    public static void startTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), () -> {
            List<Relation> ended;
            try {
                ended = tidyUp();
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Team relations could not be tidied up: {}", e.toString());
                return;
            }
            if (!ended.isEmpty()) MainThread.run(() -> ended.forEach(war -> Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§7☮ Der Krieg zwischen "
                    + label(war.teamA()) + " §7und " + label(war.teamB()) + " §7ist vorbei §7(3 Tage ohne Kill)§7."))));
        }, 20L * 60, 20L * 60 * 5);
    }


    /* queries (any thread) */

    public static @Nullable Relation between(@Nullable String team, @Nullable String other) {
        if (team == null || other == null || team.equals(other)) return null;
        return relations.get(Pair.of(team, other));
    }

    public static boolean partners(@Nullable String team, @Nullable String other) {
        Relation relation = between(team, other);
        return relation != null && relation.kind() == Kind.PARTNER;
    }

    public static boolean atWar(@Nullable String team, @Nullable String other) {
        Relation relation = between(team, other);
        return relation != null && relation.kind() == Kind.WAR;
    }

    /** The team's partnerships or wars, oldest first. */
    public static List<Relation> of(@NotNull String team, @NotNull Kind kind) {
        List<Relation> result = new ArrayList<>();
        for (Relation relation : relations.values()) {
            if (relation.kind() == kind && (relation.teamA().equals(team) || relation.teamB().equals(team))) result.add(relation);
        }
        result.sort(Comparator.comparing(Relation::since));
        return result;
    }

    public static boolean requested(@NotNull String from, @NotNull String to, @NotNull RequestKind kind) {
        return requests.containsKey(new RequestKey(from, to, kind));
    }

    /** Requests to the team, oldest first. */
    public static List<Request> incoming(@NotNull String team) {
        List<Request> result = new ArrayList<>();
        for (Request request : requests.values()) {
            if (request.to().equals(team)) result.add(request);
        }
        result.sort(Comparator.comparing(Request::at));
        return result;
    }

    /** The team's colour and name, for messages. */
    public static String label(@NotNull String teamID) {
        TeamCacheObject team = CacheHandler.getInstance().getTeamCacheObject(teamID);
        return team == null ? "§7ein altes Team" : team.getTeamColor() + team.getTeamName();
    }


    /* changes (worker thread; the caller checked that the actor may run the team) */

    /** Asks for a partnership; if the other team asked already, both agreed and they are partners. */
    public static Result requestPartnership(@NotNull String team, @NotNull String other) {
        synchronized (LOCK) {
            if (team.equals(other) || between(team, other) != null || requested(team, other, RequestKind.PARTNER)) return Result.NOT_POSSIBLE;
            if (requested(other, team, RequestKind.PARTNER)) {
                relate(team, other, Kind.PARTNER, null);
                return Result.PARTNERED;
            }
            addRequest(team, other, RequestKind.PARTNER);
            return Result.REQUESTED;
        }
    }

    public static Result acceptPartnership(@NotNull String team, @NotNull String from) {
        synchronized (LOCK) {
            if (!requested(from, team, RequestKind.PARTNER) || between(team, from) != null) return Result.NOT_POSSIBLE;
            relate(team, from, Kind.PARTNER, null);
            return Result.PARTNERED;
        }
    }

    /** One side is enough. */
    public static Result endPartnership(@NotNull String team, @NotNull String other) {
        synchronized (LOCK) {
            if (!partners(team, other)) return Result.NOT_POSSIBLE;
            unrelate(team, other);
            return Result.ENDED;
        }
    }

    /** One team declares it; not between partners (they end the partnership first). */
    public static Result declareWar(@NotNull String team, @NotNull String other) {
        synchronized (LOCK) {
            if (team.equals(other) || between(team, other) != null) return Result.NOT_POSSIBLE;
            relate(team, other, Kind.WAR, team);
            return Result.WAR;
        }
    }

    /** Offers peace; if the other team offered it already, the war is over. */
    public static Result offerPeace(@NotNull String team, @NotNull String other) {
        synchronized (LOCK) {
            if (!atWar(team, other) || requested(team, other, RequestKind.PEACE)) return Result.NOT_POSSIBLE;
            if (requested(other, team, RequestKind.PEACE)) {
                unrelate(team, other);
                return Result.PEACE;
            }
            addRequest(team, other, RequestKind.PEACE);
            return Result.PEACE_OFFERED;
        }
    }

    public static Result acceptPeace(@NotNull String team, @NotNull String from) {
        synchronized (LOCK) {
            if (!requested(from, team, RequestKind.PEACE) || !atWar(team, from)) return Result.NOT_POSSIBLE;
            unrelate(team, from);
            return Result.PEACE;
        }
    }

    public static Result withdraw(@NotNull String team, @NotNull String other, @NotNull RequestKind kind) {
        synchronized (LOCK) {
            return removeRequest(team, other, kind) ? Result.WITHDRAWN : Result.NOT_POSSIBLE;
        }
    }

    public static Result decline(@NotNull String team, @NotNull String from, @NotNull RequestKind kind) {
        synchronized (LOCK) {
            return removeRequest(from, team, kind) ? Result.DECLINED : Result.NOT_POSSIBLE;
        }
    }

    /** A dissolved team: its partnerships, wars and requests are gone (the database removed them with the team). */
    public static void forgetTeam(@NotNull String teamID) {
        synchronized (LOCK) {
            relations.keySet().removeIf(pair -> pair.a().equals(teamID) || pair.b().equals(teamID));
            requests.keySet().removeIf(key -> key.from().equals(teamID) || key.to().equals(teamID));
        }
    }

    /** After a kill between two teams: a war between them goes on. True if they are at war. */
    public static boolean warKill(@NotNull String killerTeam, @NotNull String victimTeam) {
        synchronized (LOCK) {
            Relation war = between(killerTeam, victimTeam);
            if (war == null || war.kind() != Kind.WAR) return false;
            Instant now = Instant.now();
            Database.update("UPDATE team_relations SET last_kill = ? WHERE team_a = ? AND team_b = ?", Timestamp.from(now), war.teamA(), war.teamB());
            relations.put(new Pair(war.teamA(), war.teamB()), new Relation(war.teamA(), war.teamB(), war.kind(), war.declaredBy(), war.since(), now));
            return true;
        }
    }

    /** Wars without a kill for WAR_QUIET end, requests older than REQUEST_LIFETIME go. Returns the ended wars. */
    public static List<Relation> tidyUp() {
        synchronized (LOCK) {
            Instant now = Instant.now();
            List<Relation> ended = new ArrayList<>();
            for (Relation relation : List.copyOf(relations.values())) {
                if (relation.kind() == Kind.WAR && !relation.quietEnd().isAfter(now)) {
                    unrelate(relation.teamA(), relation.teamB());
                    ended.add(relation);
                    Main.getInstance().getSLF4JLogger().info("war between {} and {} ended without kills", relation.teamA(), relation.teamB());
                }
            }
            for (Request request : List.copyOf(requests.values())) {
                if (request.at().plus(REQUEST_LIFETIME).isBefore(now)) removeRequest(request.from(), request.to(), request.kind());
            }
            return ended;
        }
    }


    /* writes (under LOCK) */

    // the relation replaces every open request between the two
    private static void relate(String team, String other, Kind kind, @Nullable String declaredBy) {
        Pair pair = Pair.of(team, other);
        Instant now = Instant.now();
        Database.inTransaction(connection -> {
            Database.update(connection, "INSERT INTO team_relations (team_a, team_b, kind, declared_by, since, last_kill) VALUES (?, ?, ?, ?, ?, ?)",
                    pair.a(), pair.b(), kind.name(), declaredBy, Timestamp.from(now), Timestamp.from(now));
            Database.update(connection, "DELETE FROM team_requests WHERE (from_team = ? AND to_team = ?) OR (from_team = ? AND to_team = ?)",
                    team, other, other, team);
            return null;
        });
        relations.put(pair, new Relation(pair.a(), pair.b(), kind, declaredBy, now, now));
        forgetRequests(team, other);
        Main.getInstance().getSLF4JLogger().info("teams {} and {}: {}{}", team, other, kind, declaredBy == null ? "" : " declared by " + declaredBy);
    }

    private static void unrelate(String team, String other) {
        Pair pair = Pair.of(team, other);
        Database.inTransaction(connection -> {
            Database.update(connection, "DELETE FROM team_relations WHERE team_a = ? AND team_b = ?", pair.a(), pair.b());
            Database.update(connection, "DELETE FROM team_requests WHERE (from_team = ? AND to_team = ?) OR (from_team = ? AND to_team = ?)",
                    team, other, other, team);
            return null;
        });
        relations.remove(pair);
        forgetRequests(team, other);
        Main.getInstance().getSLF4JLogger().info("teams {} and {}: no relation any more", team, other);
    }

    private static void addRequest(String from, String to, RequestKind kind) {
        Instant now = Instant.now();
        Database.update("INSERT INTO team_requests (from_team, to_team, kind, created_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                from, to, kind.name(), Timestamp.from(now));
        requests.put(new RequestKey(from, to, kind), new Request(from, to, kind, now));
    }

    private static boolean removeRequest(String from, String to, RequestKind kind) {
        if (!requested(from, to, kind)) return false;
        Database.update("DELETE FROM team_requests WHERE from_team = ? AND to_team = ? AND kind = ?", from, to, kind.name());
        requests.remove(new RequestKey(from, to, kind));
        return true;
    }

    private static void forgetRequests(String team, String other) {
        requests.keySet().removeIf(key -> (key.from().equals(team) && key.to().equals(other)) || (key.from().equals(other) && key.to().equals(team)));
    }

}
