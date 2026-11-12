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
import java.util.UUID;

/**
 * Bounties: a wanted player killed by a player outside their team drops their head. Whoever brings it to the Sheriff
 * gets the bounty (Loans.bounty at the time of the kill) and TEAM_POINTS for their team, once per head; never the
 * wanted player or a member of their team. The treasury pays the state's part (the points are new); while it does not
 * have the money free, the head stays unpaid and can be brought again later.
 * <p>
 * Players can put money of their own on a wanted player's head (/kopfgeld): any amount plus PLEDGE_FEE for the state.
 * The Sheriff holds it; the hunter who brings the head gets it on top. If the warrant ends without a hunter's catch, or
 * the head is not brought within PLEDGE_HEAD_DAYS, the money (not the fee) goes back. Database methods block.
 */
public final class Bounties {

    private Bounties() {}

    public static final int TEAM_POINTS = 500;
    /** What putting a bounty on a head costs, for the state (the bounty itself is the player's choice). */
    public static final int PLEDGE_FEE = 250;
    // money on a head nobody brought to the Sheriff goes back to the players after these days
    private static final int PLEDGE_HEAD_DAYS = 7;
    private static final String OPEN_PLEDGES = "SELECT COALESCE(SUM(amount), 0) FROM bounty_pledges "
            + "WHERE wanted_uuid = ? AND bounty_id IS NULL AND paid_at IS NULL AND refunded_at IS NULL";

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

    /** Worker: the bounty behind a head that just dropped; the money players put on the head goes with it. */
    public static void record(@NotNull UUID id, @NotNull UUID wanted, @NotNull String wantedName, @Nullable String wantedTeam,
                              @NotNull UUID hunter, int amount) {
        Database.inTransaction(connection -> {
            Database.update(connection, "INSERT INTO bounties (id, wanted_uuid, wanted_name, wanted_team, hunter_uuid, amount) VALUES (?, ?, ?, ?, ?, ?)",
                    id, wanted, wantedName, wantedTeam, hunter, amount);
            Database.update(connection, "UPDATE bounty_pledges SET bounty_id = ? "
                    + "WHERE wanted_uuid = ? AND bounty_id IS NULL AND paid_at IS NULL AND refunded_at IS NULL", id, wanted);
            return null;
        });
        Main.getInstance().getSLF4JLogger().info("bounty {}: {} caught by {}, worth {} from the state", id, wantedName, hunter, amount);
    }


    /* bounties of players */

    public enum PledgeOutcome { OK, NOT_WANTED, INSUFFICIENT_FUNDS, TOO_LOW, UNKNOWN }

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
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?", cost, sponsor, cost) == 0) {
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
        refunds.forEach(Bounties::announceRefund);
        return refunds;
    }

    /**
     * Worker, every few minutes: money on heads whose warrant ended without a catch (time over, bills paid), whose
     * wanted player has not been online for PLEDGE_HEAD_DAYS, or which nobody brought to the Sheriff within
     * PLEDGE_HEAD_DAYS, goes back.
     */
    public static List<Refund> refundStale() {
        return refund("""
                UPDATE bounty_pledges p SET refunded_at = now()
                WHERE p.paid_at IS NULL AND p.refunded_at IS NULL AND (
                    (p.bounty_id IS NULL AND EXISTS (SELECT 1 FROM players w WHERE w.uuid = p.wanted_uuid
                        AND ((NOT w.jail AND NOT (w.is_wanted AND (w.jail_end = 0 OR w.jail_end > ?)))
                             OR w.last_seen < now() - make_interval(days => ?))))
                    OR (p.bounty_id IS NOT NULL AND EXISTS (SELECT 1 FROM bounties b WHERE b.id = p.bounty_id
                        AND b.claimed_at IS NULL AND b.created_at < now() - make_interval(days => ?))))
                RETURNING p.sponsor_uuid, p.wanted_uuid, p.amount""", System.currentTimeMillis(), PLEDGE_HEAD_DAYS, PLEDGE_HEAD_DAYS);
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
        record Row(int amount, String name, boolean claimed) {}
        Claim claim = Database.inTransaction(connection -> {
            // the same lock as every other payout of the treasury: two of them never spend the same money
            Database.queryOne(connection, "SELECT pg_advisory_xact_lock(?)", result -> 1, Bank.INTEREST_LOCK);
            Row row = Database.queryOne(connection, "SELECT amount, wanted_name, claimed_at IS NOT NULL FROM bounties WHERE id = ? FOR UPDATE",
                    result -> new Row(result.getInt(1), result.getString(2), result.getBoolean(3)), id);
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
            int points = team != null && Database.update(connection, "UPDATE teams SET points = points + ? WHERE team_id = ?", TEAM_POINTS, team) > 0
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
