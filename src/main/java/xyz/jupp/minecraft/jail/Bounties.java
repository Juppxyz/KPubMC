package xyz.jupp.minecraft.jail;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.BankLog;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.economy.Bank;
import xyz.jupp.minecraft.economy.Treasury;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Text;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounties: a wanted player killed by a player outside their team drops their head. Whoever brings it to the Sheriff
 * gets the bounty (Loans.bounty at the time of the kill) and TEAM_POINTS for their team, once per head; never the
 * wanted player or a member of their team. The treasury pays the state's part (the points are new); while it does not
 * have the money free, the head stays unpaid and can be brought again later.
 * <p>
 * Players can put money of their own on a wanted player's head (at the Sheriff, SheriffView): any amount plus PLEDGE_FEE for the state.
 * The Sheriff holds it; the hunter who brings the head gets it on top. If the warrant ends without a hunter's catch, or
 * the head is not brought within PLEDGE_HEAD_DAYS, the money (not the fee) goes back. Database methods block.
 * <p>
 * Contracts: money (with a reason) on someone who is NOT wanted. They stay free, are not jailed and no deputy comes for
 * them; killed by a player outside their team, they drop their head like a wanted player, and the Sheriff pays the
 * players' money for it, nothing from the state and no team points. Nobody got it within CONTRACT_DAYS: the money goes back.
 */
public final class Bounties {

    private Bounties() {}

    public static final int TEAM_POINTS = 500;
    /** What putting a bounty on a head costs, for the state (the bounty itself is the player's choice). */
    public static final int PLEDGE_FEE = 250;
    // money on a head nobody brought to the Sheriff goes back to the players after these days
    private static final int PLEDGE_HEAD_DAYS = 7;
    /** A contract nobody fulfilled goes back after these days. */
    public static final int CONTRACT_DAYS = 7;
    public static final int REASON_MIN = 3;
    public static final int REASON_MAX = 60;
    private static final String OPEN_PLEDGES = "SELECT COALESCE(SUM(amount), 0) FROM bounty_pledges "
            + "WHERE wanted_uuid = ? AND bounty_id IS NULL AND paid_at IS NULL AND refunded_at IS NULL";
    private static final String OPEN_CONTRACTS = "SELECT COALESCE(SUM(amount), 0) FROM bounty_pledges "
            + "WHERE wanted_uuid = ? AND contract AND bounty_id IS NULL AND paid_at IS NULL AND refunded_at IS NULL";

    // who has an open contract on their head, for the death on the main thread (no database there)
    private static final Map<UUID, Long> contracts = new ConcurrentHashMap<>();

    /** Blocking, in onEnable: the open contracts. */
    public static int load() {
        contracts.clear();
        Database.query("SELECT wanted_uuid, SUM(amount) FROM bounty_pledges WHERE contract AND bounty_id IS NULL "
                + "AND paid_at IS NULL AND refunded_at IS NULL GROUP BY wanted_uuid", row -> {
            contracts.put(row.getObject(1, UUID.class), row.getLong(2));
            return null;
        });
        return contracts.size();
    }

    /** Any thread: there is an open contract on this player's head. */
    public static boolean hasContract(@NotNull UUID player) {
        return contracts.containsKey(player);
    }

    // blocking: after a contract was put up, went with a head or went back
    private static void refreshContract(@NotNull UUID player) {
        Long open = Database.queryOne(OPEN_CONTRACTS, row -> row.getLong(1), player);
        if (open == null || open <= 0) contracts.remove(player);
        else contracts.put(player, open);
    }

    private static final NamespacedKey ID = new NamespacedKey(Main.getInstance(), "bounty");
    private static final NamespacedKey WANTED = new NamespacedKey(Main.getInstance(), "bounty_wanted");
    private static final NamespacedKey TEAM = new NamespacedKey(Main.getInstance(), "bounty_team");

    /** What a head says about its bounty, read from the item. */
    public record Head(UUID id, UUID wanted, @Nullable String wantedTeam) {}

    /** Main thread: the head of the wanted player; the bounty itself is written by {@link #record}. */
    public static ItemStack head(@NotNull UUID id, @NotNull Player wanted, @Nullable String wantedTeam, @NotNull String hunter) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(wanted);
        meta.customName(Text.of("§c§lKopf von " + wanted.getName()));
        meta.lore(Text.lore(List.of(
                "§7Gefasst von §f" + hunter,
                "§7Der §9Sheriff §7zahlt dafür",
                "§7das Kopfgeld.")));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(ID, PersistentDataType.STRING, id.toString());
        data.set(WANTED, PersistentDataType.STRING, wanted.getUniqueId().toString());
        if (wantedTeam != null) data.set(TEAM, PersistentDataType.STRING, wantedTeam);
        head.setItemMeta(meta);
        return head;
    }

    /** The bounty of a head, null for any other item. */
    public static @Nullable Head read(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String id = data.get(ID, PersistentDataType.STRING);
        String wanted = data.get(WANTED, PersistentDataType.STRING);
        if (id == null || wanted == null) return null;
        try {
            return new Head(UUID.fromString(id), UUID.fromString(wanted), data.get(TEAM, PersistentDataType.STRING));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Worker: the bounty behind a head that just dropped; the money players put on the head goes with it. contract: the
     * player was not wanted, only players' money is on the head (amount 0, no team points at the Sheriff).
     */
    public static void record(@NotNull UUID id, @NotNull UUID wanted, @NotNull String wantedName, @Nullable String wantedTeam,
                              @NotNull UUID hunter, int amount, boolean contract) {
        Database.inTransaction(connection -> {
            Database.update(connection, "INSERT INTO bounties (id, wanted_uuid, wanted_name, wanted_team, hunter_uuid, amount, contract) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, wanted, wantedName, wantedTeam, hunter, amount, contract);
            Database.update(connection, "UPDATE bounty_pledges SET bounty_id = ? "
                    + "WHERE wanted_uuid = ? AND bounty_id IS NULL AND paid_at IS NULL AND refunded_at IS NULL", id, wanted);
            return null;
        });
        refreshContract(wanted);
        Main.getInstance().getSLF4JLogger().info("bounty {}: {} caught by {}, worth {} from the state{}", id, wantedName, hunter, amount,
                contract ? " (contract)" : "");
    }


    /* bounties of players */

    public enum PledgeOutcome { OK, NOT_WANTED, INSUFFICIENT_FUNDS, TOO_LOW, UNKNOWN, SELF, SAME_TEAM }

    /** total: what players have put on that head so far, this one included. */
    public record Pledge(PledgeOutcome outcome, long total) {}

    /** Worker: the sponsor puts amount on a wanted player's head and pays PLEDGE_FEE to the state. */
    public static Pledge pledge(@NotNull UUID sponsor, @NotNull UUID wanted, int amount) {
        if (amount < 1) return new Pledge(PledgeOutcome.TOO_LOW, 0);
        Pledge pledge = Database.inTransaction(connection -> {
            Boolean active = Database.queryOne(connection, "SELECT is_wanted AND NOT jail AND (jail_end = 0 OR jail_end > ?) FROM players WHERE uuid = ? FOR UPDATE",
                    row -> row.getBoolean(1), System.currentTimeMillis(), wanted);
            if (active == null) return new Pledge(PledgeOutcome.UNKNOWN, 0);
            if (!active) return new Pledge(PledgeOutcome.NOT_WANTED, 0);
            long cost = (long) amount + PLEDGE_FEE;
            // money bound to a loan does not go to a hunter
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money - ? >= " + BankLog.LOAN_LOCK,
                    cost, sponsor, cost, sponsor) == 0) {
                return new Pledge(PledgeOutcome.INSUFFICIENT_FUNDS, 0);
            }
            Database.update(connection, "INSERT INTO bounty_pledges (wanted_uuid, sponsor_uuid, amount) VALUES (?, ?, ?)", wanted, sponsor, amount);
            Treasury.deposit(connection, Treasury.Source.FEES, PLEDGE_FEE, sponsor);
            BankLog.add(connection, sponsor, BankLog.PLEDGE, -amount, wanted.toString());
            Long total = Database.queryOne(connection, OPEN_PLEDGES, row -> row.getLong(1), wanted);
            return new Pledge(PledgeOutcome.OK, total == null ? amount : total);
        });
        if (pledge.outcome() == PledgeOutcome.OK) Treasury.committed(PLEDGE_FEE);
        return pledge;
    }

    /**
     * Worker: the sponsor puts amount on anyone's head with a reason, and pays PLEDGE_FEE to the state. Someone who is not
     * wanted gets a contract (they stay free); on a wanted player it is an ordinary pledge (with the reason).
     */
    public static Pledge pledgeContract(@NotNull UUID sponsor, @NotNull UUID target, int amount, @NotNull String reason) {
        if (amount < 1) return new Pledge(PledgeOutcome.TOO_LOW, 0);
        if (sponsor.equals(target)) return new Pledge(PledgeOutcome.SELF, 0);
        record Target(boolean wanted, @Nullable String team) {}
        Pledge pledge = Database.inTransaction(connection -> {
            Target found = Database.queryOne(connection, "SELECT is_wanted AND NOT jail AND (jail_end = 0 OR jail_end > ?), team_id FROM players WHERE uuid = ? FOR UPDATE",
                    row -> new Target(row.getBoolean(1), row.getString(2)), System.currentTimeMillis(), target);
            if (found == null) return new Pledge(PledgeOutcome.UNKNOWN, 0);
            // no contract within a team: its members could not even take the head
            String sponsorTeam = Database.queryOne(connection, "SELECT team_id FROM players WHERE uuid = ?", row -> row.getString(1), sponsor);
            if (found.team() != null && found.team().equals(sponsorTeam)) return new Pledge(PledgeOutcome.SAME_TEAM, 0);
            long cost = (long) amount + PLEDGE_FEE;
            // money bound to a loan does not go to a hunter
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money - ? >= " + BankLog.LOAN_LOCK,
                    cost, sponsor, cost, sponsor) == 0) {
                return new Pledge(PledgeOutcome.INSUFFICIENT_FUNDS, 0);
            }
            Database.update(connection, "INSERT INTO bounty_pledges (wanted_uuid, sponsor_uuid, amount, reason, contract) VALUES (?, ?, ?, ?, ?)",
                    target, sponsor, amount, reason, !found.wanted());
            Treasury.deposit(connection, Treasury.Source.FEES, PLEDGE_FEE, sponsor);
            BankLog.add(connection, sponsor, BankLog.PLEDGE, -amount, target.toString());
            Long total = Database.queryOne(connection, OPEN_PLEDGES, row -> row.getLong(1), target);
            return new Pledge(PledgeOutcome.OK, total == null ? amount : total);
        });
        if (pledge.outcome() == PledgeOutcome.OK) {
            Treasury.committed(PLEDGE_FEE);
            refreshContract(target);
        }
        return pledge;
    }

    /** A contract on someone's head (not wanted): who, how much, the newest reason. */
    public record Contract(UUID target, long amount, @Nullable String reason) {}

    /** Blocking: the open contracts, the biggest first. */
    public static List<Contract> openContracts(int limit) {
        return Database.query("SELECT wanted_uuid, SUM(amount), (array_agg(reason ORDER BY created_at DESC))[1] FROM bounty_pledges "
                + "WHERE contract AND bounty_id IS NULL AND paid_at IS NULL AND refunded_at IS NULL "
                + "GROUP BY wanted_uuid ORDER BY 2 DESC LIMIT ?", row -> new Contract(row.getObject(1, UUID.class), row.getLong(2), row.getString(3)), limit);
    }

    // inside the claim's transaction: the players' money on the head to the hunter
    private static void payPledges(java.sql.Connection connection, UUID id, UUID player, long pledged, String wantedName) throws java.sql.SQLException {
        Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", pledged, player);
        Database.update(connection, "UPDATE bounty_pledges SET paid_at = now() WHERE bounty_id = ? AND paid_at IS NULL AND refunded_at IS NULL", id);
        BankLog.add(connection, player, BankLog.BOUNTY, pledged, wantedName + " (Spieler)");
    }

    /** Blocking: what players have put on this wanted player's head (not yet with a dropped head). */
    public static long pledged(@NotNull UUID wanted) {
        Long total = Database.queryOne(OPEN_PLEDGES, row -> row.getLong(1), wanted);
        return total == null ? 0 : total;
    }

    /** A pledge that went back to its sponsor. */
    public record Refund(UUID sponsor, UUID wanted, int amount) {}

    /** Blocking: the warrant ended without a hunter's catch (jail, an admin): the money on the head goes back. */
    public static List<Refund> refundOpen(@NotNull UUID wanted) {
        List<Refund> refunds = refund("UPDATE bounty_pledges SET refunded_at = now() "
                + "WHERE wanted_uuid = ? AND bounty_id IS NULL AND paid_at IS NULL AND refunded_at IS NULL "
                + "RETURNING sponsor_uuid, wanted_uuid, amount", wanted);
        refreshContract(wanted);
        refunds.forEach(Bounties::announceRefund);
        return refunds;
    }

    /**
     * Worker, every few minutes: money on heads whose warrant ended without a catch (time over, bills paid), whose
     * wanted player has not been online for PLEDGE_HEAD_DAYS, or which nobody brought to the Sheriff within
     * PLEDGE_HEAD_DAYS, goes back; so does a contract nobody fulfilled within CONTRACT_DAYS (its player was never wanted).
     */
    public static List<Refund> refundStale() {
        List<Refund> refunds = refund("""
                UPDATE bounty_pledges p SET refunded_at = now()
                WHERE p.paid_at IS NULL AND p.refunded_at IS NULL AND (
                    (p.bounty_id IS NULL AND NOT p.contract AND EXISTS (SELECT 1 FROM players w WHERE w.uuid = p.wanted_uuid
                        AND ((NOT w.jail AND NOT (w.is_wanted AND (w.jail_end = 0 OR w.jail_end > ?)))
                             OR w.last_seen < now() - make_interval(days => ?))))
                    OR (p.bounty_id IS NULL AND p.contract AND p.created_at < now() - make_interval(days => ?))
                    OR (p.bounty_id IS NOT NULL AND EXISTS (SELECT 1 FROM bounties b WHERE b.id = p.bounty_id
                        AND b.claimed_at IS NULL AND b.created_at < now() - make_interval(days => ?))))
                RETURNING p.sponsor_uuid, p.wanted_uuid, p.amount""",
                System.currentTimeMillis(), PLEDGE_HEAD_DAYS, CONTRACT_DAYS, PLEDGE_HEAD_DAYS);
        refunds.stream().map(Refund::wanted).distinct().forEach(Bounties::refreshContract);
        return refunds;
    }

    // marks the pledges refunded and pays them back in one transaction
    private static List<Refund> refund(String sql, Object... params) {
        return Database.inTransaction(connection -> {
            List<Refund> refunds = Database.query(connection, sql, row -> new Refund(row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class), row.getInt(3)), params);
            for (Refund refund : refunds) {
                Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", refund.amount(), refund.sponsor());
                BankLog.add(connection, refund.sponsor(), BankLog.PLEDGE_REFUND, refund.amount(), refund.wanted().toString());
            }
            return refunds;
        });
    }

    /** Any thread: tells an online sponsor that their money is back. */
    public static void announceRefund(@NotNull Refund refund) {
        MainThread.run(() -> {
            Player sponsor = Bukkit.getPlayer(refund.sponsor());
            if (sponsor == null) return;
            sponsor.sendMessage(Main.getChatPrefix() + "§fDein Kopfgeld von §a" + refund.amount()
                    + " Schilling §fist zurück auf deinem Konto: niemand hat den Kopf zum §9Sheriff §fgebracht.");
        });
    }

    public enum Outcome { PAID, ALREADY_PAID, UNKNOWN, TREASURY_EMPTY }

    /**
     * amount: all the hunter got now (the state's part plus what players put on the head); points: what the team got;
     * pending: the state's part that waits for money in the treasury (TREASURY_EMPTY, the head stays).
     */
    public record Claim(UUID id, Outcome outcome, @Nullable String wantedName, int amount, int points, int fromState, int pending) {}

    /** Worker: pays the bounty of a head to the player (and TEAM_POINTS to their team), once. */
    public static Claim claim(@NotNull UUID id, @NotNull UUID player, @Nullable String team) {
        record Row(int amount, String name, boolean claimed, boolean contract) {}
        Claim claim = Database.inTransaction(connection -> {
            // the same lock as every other payout of the treasury: two of them never spend the same money
            Database.queryOne(connection, "SELECT pg_advisory_xact_lock(?)", result -> 1, Bank.INTEREST_LOCK);
            Row row = Database.queryOne(connection, "SELECT amount, wanted_name, claimed_at IS NOT NULL, contract FROM bounties WHERE id = ? FOR UPDATE",
                    result -> new Row(result.getInt(1), result.getString(2), result.getBoolean(3), result.getBoolean(4)), id);
            if (row == null) return new Claim(id, Outcome.UNKNOWN, null, 0, 0, 0, 0);
            if (row.claimed()) return new Claim(id, Outcome.ALREADY_PAID, row.name(), 0, 0, 0, 0);
            // the money players put on this head, locked so a refund at the same moment cannot pay it out twice
            long pledged = 0;
            for (Integer part : Database.query(connection, "SELECT amount FROM bounty_pledges WHERE bounty_id = ? "
                    + "AND paid_at IS NULL AND refunded_at IS NULL FOR UPDATE", result -> result.getInt(1), id)) pledged += part;
            if (row.amount() > 0) {
                // only from the state's own money: not what it owes to savers and bond holders
                Long free = Database.queryOne(connection, "SELECT (SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger) "
                        + "- (SELECT COALESCE(SUM(amount), 0) FROM bank_deposits WHERE NOT paid_out AND in_treasury) "
                        + "- (SELECT COALESCE(SUM(payout), 0) FROM state_bonds WHERE NOT repaid)", result -> result.getLong(1));
                if (free == null || free < row.amount()) {
                    // the players' money is paid now, the state's part waits (the head stays with the hunter)
                    if (pledged > 0) payPledges(connection, id, player, pledged, row.name());
                    return new Claim(id, Outcome.TREASURY_EMPTY, row.name(), (int) Math.min(Integer.MAX_VALUE, pledged), 0, 0, row.amount());
                }
            }
            Database.update(connection, "UPDATE bounties SET claimed_by = ?, claimed_at = now() WHERE id = ?", player, id);
            long total = row.amount() + pledged;
            if (pledged > 0) payPledges(connection, id, player, pledged, row.name());
            if (row.amount() > 0) {
                Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", row.amount(), player);
                Treasury.withdraw(connection, Treasury.Source.BOUNTY, row.amount(), player);
                BankLog.add(connection, player, BankLog.BOUNTY, row.amount(), row.name());
            }
            // a contract pays only the players' money: points for it could be farmed with a friend for the fee
            int points = !row.contract() && team != null && Database.update(connection, "UPDATE teams SET points = points + ? WHERE team_id = ?", TEAM_POINTS, team) > 0
                    ? TEAM_POINTS : 0;
            return new Claim(id, Outcome.PAID, row.name(), (int) Math.min(Integer.MAX_VALUE, total), points, row.amount(), 0);
        });
        if (claim.outcome() == Outcome.PAID) {
            Treasury.committed(-claim.fromState());
            Main.getInstance().getSLF4JLogger().info("bounty {} for {} paid to {}: +{} money, +{} team points ({})",
                    id, claim.wantedName(), player, claim.amount(), claim.points(), team);
        }
        return claim;
    }

}
