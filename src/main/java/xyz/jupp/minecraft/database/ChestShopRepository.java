package xyz.jupp.minecraft.database;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * The money side of the players' chest shops: the buyer pays first, the seller gets the money once the goods were taken
 * out of the chest, every booking on both statements (bank_log) and every sale in chest_shop_sales (trade between
 * players, for the economy statistics). No tax: money should be the way players pay each other. Blocking.
 */
public final class ChestShopRepository {

    private ChestShopRepository() {}

    public static final String BUY = "SHOP_BUY";
    public static final String SALE = "SHOP_SALE";
    public static final String REFUND = "SHOP_REFUND";

    /** The buyer pays; false (nothing booked) if the money is not there. note: the seller. */
    public enum Payment { OK, INSUFFICIENT_FUNDS, SAME_TEAM }

    /**
     * The buyer pays (the seller is credited by {@link #sold} after the hand-over). Not between members of one team: a
     * team's shops are no way to move money between its members.
     */
    public static Payment pay(@NotNull UUID buyer, @NotNull UUID seller, int price) {
        return Database.inTransaction(connection -> {
            Boolean sameTeam = Database.queryOne(connection, "SELECT EXISTS (SELECT 1 FROM team_members a JOIN team_members b "
                    + "ON a.team_id = b.team_id WHERE a.uuid = ? AND b.uuid = ?)", row -> row.getBoolean(1), buyer, seller);
            if (Boolean.TRUE.equals(sameTeam)) return Payment.SAME_TEAM;
            // money bound to a loan does not go to other players
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money - ? >= " + BankLog.LOAN_LOCK,
                    price, buyer, price, buyer) == 0) {
                return Payment.INSUFFICIENT_FUNDS;
            }
            BankLog.add(connection, buyer, BUY, -price, seller.toString());
            return Payment.OK;
        });
    }

    /** The goods could not be handed over: the buyer gets the money back. */
    public static void refund(@NotNull UUID buyer, @NotNull UUID seller, int price) {
        Database.inTransaction(connection -> {
            Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", price, buyer);
            BankLog.add(connection, buyer, REFUND, price, seller.toString());
            return null;
        });
    }

    /** The goods went to the buyer: the seller gets the money, the sale is logged. */
    public static void sold(@NotNull UUID seller, @NotNull UUID buyer, @NotNull Material material, int quantity, int price) {
        Database.inTransaction(connection -> {
            // the shop stands in the world, the owner's account may not exist (yet) after a season reset: created here,
            // so the money is not lost
            Database.update(connection, "INSERT INTO players (uuid) VALUES (?) ON CONFLICT (uuid) DO NOTHING", seller);
            Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", price, seller);
            BankLog.add(connection, seller, SALE, price, buyer.toString());
            Database.update(connection, "INSERT INTO chest_shop_sales (seller_uuid, buyer_uuid, material, quantity, price) VALUES (?, ?, ?, ?, ?)",
                    seller, buyer, material.name(), quantity, price);
            return null;
        });
    }

}
