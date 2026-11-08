package xyz.jupp.minecraft.jail;

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
import xyz.jupp.minecraft.utils.Text;

import java.util.List;
import java.util.UUID;

/**
 * Bounties: a wanted player killed by a player outside their team drops their head. Whoever brings it to the Sheriff
 * gets the bounty (Loans.bounty at the time of the kill) and TEAM_POINTS for their team, once per head; never the
 * wanted player or a member of their team. The money and the points are new. Database methods block.
 */
public final class Bounties {

    private Bounties() {}

    public static final int TEAM_POINTS = 500;

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

    /** Worker: the bounty behind a head that just dropped. */
    public static void record(@NotNull UUID id, @NotNull UUID wanted, @NotNull String wantedName, @Nullable String wantedTeam,
                              @NotNull UUID hunter, int amount) {
        Database.update("INSERT INTO bounties (id, wanted_uuid, wanted_name, wanted_team, hunter_uuid, amount) VALUES (?, ?, ?, ?, ?, ?)",
                id, wanted, wantedName, wantedTeam, hunter, amount);
        Main.getInstance().getSLF4JLogger().info("bounty {}: {} caught by {}, worth {}", id, wantedName, hunter, amount);
    }

    public enum Outcome { PAID, ALREADY_PAID, UNKNOWN }

    /** points: what the team got (0 without a team). */
    public record Claim(UUID id, Outcome outcome, @Nullable String wantedName, int amount, int points) {}

    /** Worker: pays the bounty of a head to the player (and TEAM_POINTS to their team), once. */
    public static Claim claim(@NotNull UUID id, @NotNull UUID player, @Nullable String team) {
        record Row(int amount, String name, boolean claimed) {}
        Claim claim = Database.inTransaction(connection -> {
            Row row = Database.queryOne(connection, "SELECT amount, wanted_name, claimed_at IS NOT NULL FROM bounties WHERE id = ? FOR UPDATE",
                    result -> new Row(result.getInt(1), result.getString(2), result.getBoolean(3)), id);
            if (row == null) return new Claim(id, Outcome.UNKNOWN, null, 0, 0);
            if (row.claimed()) return new Claim(id, Outcome.ALREADY_PAID, row.name(), 0, 0);
            Database.update(connection, "UPDATE bounties SET claimed_by = ?, claimed_at = now() WHERE id = ?", player, id);
            if (row.amount() > 0) {
                Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", row.amount(), player);
                BankLog.add(connection, player, BankLog.BOUNTY, row.amount(), row.name());
            }
            int points = team != null && Database.update(connection, "UPDATE teams SET points = points + ? WHERE team_id = ?", TEAM_POINTS, team) > 0
                    ? TEAM_POINTS : 0;
            return new Claim(id, Outcome.PAID, row.name(), row.amount(), points);
        });
        if (claim.outcome() == Outcome.PAID) {
            Main.getInstance().getSLF4JLogger().info("bounty {} for {} paid to {}: +{} money, +{} team points ({})",
                    id, claim.wantedName(), player, claim.amount(), claim.points(), team);
        }
        return claim;
    }

}
