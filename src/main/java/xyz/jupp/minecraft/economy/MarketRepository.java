package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tables market_items, market_rotation and market_transactions. Every method is blocking.
 * Trades lock the catalog row, so parallel trades of the same item are priced one after another.
 */
public final class MarketRepository {

    private MarketRepository() {}

    public enum Outcome { OK, INSUFFICIENT_FUNDS, PRICE_CHANGED, UNAVAILABLE }

    /** Result of a trade; item is the catalog entry with the new demand (null if the item does not exist). */
    public record Trade(Outcome outcome, int net, int tax, @Nullable MarketItem item) {}

    // columns /shopadmin may change, with their SQL type check done by the database
    public static final Map<String, String> EDITABLE_COLUMNS = Map.ofEntries(
            Map.entry("preis", "base_price"),
            Map.entry("min", "min_price"),
            Map.entry("max", "max_price"),
            Map.entry("menge", "amount"),
            Map.entry("ankauf", "sell_ratio"),
            Map.entry("elastizitaet", "elasticity"),
            Map.entry("kategorie", "category"),
            Map.entry("fest", "core"),
            Map.entry("gewicht", "rotation_weight"),
            Map.entry("aktiv", "enabled"),
            Map.entry("kaufbar", "buyable"),
            Map.entry("verkaufbar", "sellable"),
            Map.entry("name", "display_name"),
            Map.entry("beschreibung", "description"));

    private static final String COLUMNS = "material, category, display_name, description, amount, base_price, min_price, "
            + "max_price, elasticity, sell_ratio, buyable, sellable, core, rotation_weight, enabled, demand";

    private static @Nullable MarketItem map(ResultSet row) throws SQLException {
        Material material = Material.matchMaterial(row.getString("material"));
        Category category = Category.parse(row.getString("category"));
        if (material == null || category == null) {
            Main.getInstance().getSLF4JLogger().warn("Skipping market item {} with unknown material or category {}",
                    row.getString("material"), row.getString("category"));
            return null;
        }
        return new MarketItem(material, category,
                row.getString("display_name"),
                row.getString("description"),
                row.getInt("amount"),
                row.getInt("base_price"),
                row.getObject("min_price", Integer.class),
                row.getObject("max_price", Integer.class),
                row.getDouble("elasticity"),
                row.getDouble("sell_ratio"),
                row.getBoolean("buyable"),
                row.getBoolean("sellable"),
                row.getBoolean("core"),
                row.getInt("rotation_weight"),
                row.getBoolean("enabled"),
                row.getDouble("demand"));
    }


    /* catalog */

    public static List<MarketItem> loadAll() {
        return Database.query("SELECT " + COLUMNS + " FROM market_items", MarketRepository::map)
                .stream().filter(Objects::nonNull).toList();
    }

    public static int count() {
        Integer count = Database.queryOne("SELECT COUNT(*) FROM market_items", row -> row.getInt(1));
        return count == null ? 0 : count;
    }

    /** Inserts the item unless the material already exists; true if inserted. */
    public static boolean insert(@NotNull MarketItem item) {
        return Database.update("INSERT INTO market_items (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (material) DO NOTHING",
                item.material().name(), item.category().name(), item.displayName(), item.description(), item.amount(),
                item.basePrice(), item.minPrice(), item.maxPrice(), item.elasticity(), item.sellRatio(), item.buyable(),
                item.sellable(), item.core(), item.rotationWeight(), item.enabled(), item.demand()) > 0;
    }

    /** Sets one editable column (see {@link #EDITABLE_COLUMNS}); false if the material is not in the catalog. */
    public static boolean update(@NotNull Material material, @NotNull String column, @Nullable Object value) {
        if (!EDITABLE_COLUMNS.containsValue(column)) throw new IllegalArgumentException("not editable: " + column);
        return Database.update("UPDATE market_items SET " + column + " = ?, updated_at = now() WHERE material = ?",
                value, material.name()) > 0;
    }

    public static void resetDemand(@Nullable Material material) {
        if (material == null) {
            Database.update("UPDATE market_items SET demand = 0");
        } else {
            Database.update("UPDATE market_items SET demand = 0 WHERE material = ?", material.name());
        }
    }

    /** Moves every demand towards 0; tiny rests are set to 0. */
    public static void decayDemand(double factor) {
        Database.update("UPDATE market_items SET demand = CASE WHEN abs(demand * ?) < 0.05 THEN 0 ELSE demand * ? END WHERE demand <> 0",
                factor, factor);
    }


    /* daily offers */

    public static List<Material> loadRotation(@NotNull LocalDate day) {
        return Database.query("SELECT material FROM market_rotation WHERE day = ? ORDER BY slot",
                row -> Material.matchMaterial(row.getString(1)), Date.valueOf(day)).stream().filter(Objects::nonNull).toList();
    }

    public static void saveRotation(@NotNull LocalDate day, @NotNull List<Material> materials) {
        Database.inTransaction(connection -> {
            Database.update(connection, "DELETE FROM market_rotation WHERE day = ?", Date.valueOf(day));
            for (int slot = 0; slot < materials.size(); slot++) {
                Database.update(connection, "INSERT INTO market_rotation (day, slot, material) VALUES (?, ?, ?)",
                        Date.valueOf(day), slot, materials.get(slot).name());
            }
            return null;
        });
    }


    /* trades */

    private static @Nullable MarketItem lock(Connection connection, Material material) throws SQLException {
        return Database.queryOne(connection, "SELECT " + COLUMNS + " FROM market_items WHERE material = ? FOR UPDATE",
                MarketRepository::map, material.name());
    }

    /**
     * Buys n bundles. expectedNet is the price the player saw; if the current price differs, nothing happens
     * and PRICE_CHANGED is returned (null skips the check).
     */
    public static Trade buy(@NotNull UUID player, @NotNull Material material, int bundles, double discount,
                            @Nullable Integer expectedNet) {
        Trade trade = Database.inTransaction(connection -> {
            MarketItem item = lock(connection, material);
            if (item == null || !item.enabled() || !item.buyable()) return new Trade(Outcome.UNAVAILABLE, 0, 0, item);

            int net = item.buyTotal(bundles, discount);
            int tax = Taxes.tradeTaxOn(net);
            if (expectedNet != null && expectedNet != net) return new Trade(Outcome.PRICE_CHANGED, net, tax, item);

            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?",
                    net + tax, player, net + tax) == 0) {
                return new Trade(Outcome.INSUFFICIENT_FUNDS, net, tax, item);
            }
            double demand = item.demand() + bundles;
            Database.update(connection, "UPDATE market_items SET demand = ? WHERE material = ?", demand, material.name());
            logTrade(connection, player, material, "BUY", bundles * item.amount(), net, tax);
            Treasury.deposit(connection, Treasury.Source.TRADE_TAX, tax, player);
            return new Trade(Outcome.OK, net, tax, item.withDemand(demand));
        });
        if (trade.outcome() == Outcome.OK) Treasury.committed(trade.tax());
        return trade;
    }

    /** Sells n bundles (the items were already taken from the player). expectedNet as in {@link #buy}. */
    public static Trade sell(@NotNull UUID player, @NotNull Material material, int bundles, @Nullable Integer expectedNet) {
        return Database.inTransaction(connection -> {
            MarketItem item = lock(connection, material);
            if (item == null || !item.enabled() || !item.sellable()) return new Trade(Outcome.UNAVAILABLE, 0, 0, item);

            int payout = item.sellTotal(bundles);
            if (expectedNet != null && expectedNet != payout) return new Trade(Outcome.PRICE_CHANGED, payout, 0, item);

            if (Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", payout, player) == 0) {
                return new Trade(Outcome.UNAVAILABLE, payout, 0, item);
            }
            double demand = item.demand() - bundles;
            Database.update(connection, "UPDATE market_items SET demand = ? WHERE material = ?", demand, material.name());
            logTrade(connection, player, material, "SELL", bundles * item.amount(), payout, 0);
            return new Trade(Outcome.OK, payout, 0, item.withDemand(demand));
        });
    }

    /** A purchase with a fixed price that does not move the market (random item). */
    public static Trade buyFixed(@NotNull UUID player, @NotNull Material material, int quantity, int net) {
        int tax = Taxes.tradeTaxOn(net);
        Trade trade = Database.inTransaction(connection -> {
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?",
                    net + tax, player, net + tax) == 0) {
                return new Trade(Outcome.INSUFFICIENT_FUNDS, net, tax, null);
            }
            logTrade(connection, player, material, "BUY", quantity, net, tax);
            Treasury.deposit(connection, Treasury.Source.TRADE_TAX, tax, player);
            return new Trade(Outcome.OK, net, tax, null);
        });
        if (trade.outcome() == Outcome.OK) Treasury.committed(tax);
        return trade;
    }

    private static void logTrade(Connection connection, UUID player, Material material, String kind, int quantity,
                                 int net, int tax) throws SQLException {
        Database.update(connection, "INSERT INTO market_transactions (player_uuid, material, kind, quantity, net, tax) VALUES (?, ?, ?, ?, ?, ?)",
                player, material.name(), kind, quantity, net, tax);
    }

    /** Traded quantities per material in the last days (for /shopadmin info). */
    public static List<long[]> volumeSince(@NotNull Material material, int days) {
        List<long[]> result = new ArrayList<>();
        Database.query("SELECT kind, COALESCE(SUM(quantity), 0), COALESCE(SUM(net), 0) FROM market_transactions "
                        + "WHERE material = ? AND created_at >= now() - make_interval(days => ?) GROUP BY kind",
                row -> result.add(new long[]{"BUY".equals(row.getString(1)) ? 1 : 0, row.getLong(2), row.getLong(3)}),
                material.name(), days);
        return result;
    }

}
