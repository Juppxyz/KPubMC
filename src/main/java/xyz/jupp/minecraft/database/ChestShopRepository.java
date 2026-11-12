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
    public static boolean pay(@NotNull UUID buyer, @NotNull UUID seller, int price) {
        return Database.inTransaction(connection -> {
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?", price, buyer, price) == 0) {
                return false;
            }
            BankLog.add(connection, buyer, BUY, -price, seller.toString());
            return true;
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
            Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", price, seller);
            BankLog.add(connection, seller, SALE, price, buyer.toString());
            Database.update(connection, "INSERT INTO chest_shop_sales (seller_uuid, buyer_uuid, material, quantity, price) VALUES (?, ?, ?, ?, ?)",
                    seller, buyer, material.name(), quantity, price);
            return null;
        });
    }

}
