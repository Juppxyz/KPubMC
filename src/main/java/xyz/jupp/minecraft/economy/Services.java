package xyz.jupp.minecraft.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.utils.Text;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Effects and services of the shop: catalog (table market_services, defaults below), purchase as one transaction,
 * and applying them in the game (main thread). Combat buffs (strength, resistance, regeneration) are deliberately
 * not offered.
 */
public final class Services {

    private Services() {}

    private static final List<ServiceOffer> DEFAULTS = List.of(
            effect("haste", "§6Eile II", Material.GOLDEN_PICKAXE, 400, "haste", 1, 600, 3600, 10),
            effect("night_vision", "§9Nachtsicht", Material.ENDER_EYE, 250, "night_vision", 0, 1800, 7200, 20),
            effect("fire_resistance", "§cFeuerresistenz", Material.MAGMA_CREAM, 500, "fire_resistance", 0, 600, 3600, 30),
            effect("water_breathing", "§bWasseratmung", Material.TURTLE_HELMET, 300, "water_breathing", 0, 900, 3600, 40),
            effect("speed", "§fTempo", Material.SUGAR, 300, "speed", 0, 600, 3600, 50),
            effect("slow_falling", "§fSanfter Fall", Material.FEATHER, 350, "slow_falling", 0, 600, 3600, 60),
            effect("luck", "§aGlück", Material.RABBIT_FOOT, 600, "luck", 0, 1800, 7200, 70),
            // price per 100 points of durability
            new ServiceOffer("repair", ServiceOffer.Kind.REPAIR, "§7Reparieren", Material.ANVIL, 60, 0.02, TaxClass.STANDARD,
                    null, 0, 0, 0, 100, true, 0),
            // duration = how long the weather stays clear
            new ServiceOffer("weather", ServiceOffer.Kind.WEATHER, "§eSonne kaufen", Material.SUNFLOWER, 2000, 0.3, TaxClass.LUXURY,
                    null, 0, 1200, 0, 110, true, 0),
            new ServiceOffer("day", ServiceOffer.Kind.DAY, "§eTag kaufen", Material.CLOCK, 1500, 0.3, TaxClass.LUXURY,
                    null, 0, 0, 0, 120, true, 0));

    private static ServiceOffer effect(String key, String name, Material icon, int price, String effect, int amplifier,
                                       int seconds, int maxSeconds, int sort) {
        return new ServiceOffer(key, ServiceOffer.Kind.EFFECT, name, icon, price, 0.05, TaxClass.STANDARD,
                effect, amplifier, seconds, maxSeconds, sort, true, 0);
    }

    private static final String COLUMNS = "key, kind, display_name, icon, base_price, elasticity, tax_class, effect, "
            + "amplifier, duration_seconds, max_seconds, sort, enabled, demand";

    // /shopadmin dienst <key> <field> <value>
    public static final Map<String, String> EDITABLE_COLUMNS = Map.of(
            "preis", "base_price",
            "elastizitaet", "elasticity",
            "steuerklasse", "tax_class",
            "stufe", "amplifier",
            "dauer", "duration_seconds",
            "maxdauer", "max_seconds",
            "aktiv", "enabled",
            "name", "display_name");

    private static final Map<String, ServiceOffer> offers = new ConcurrentHashMap<>();

    public record Purchase(MarketRepository.Outcome outcome, int net, int tax, @Nullable ServiceOffer offer) {
        public int total() {
            return net + tax;
        }
    }


    /* catalog (blocking) */

    public static void load() {
        for (ServiceOffer offer : DEFAULTS) {
            Database.update("INSERT INTO market_services (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (key) DO NOTHING",
                    offer.key(), offer.kind().name(), offer.displayName(), offer.icon().name(), offer.basePrice(), offer.elasticity(),
                    offer.taxClass().name(), offer.effect(), offer.amplifier(), offer.durationSeconds(), offer.maxSeconds(),
                    offer.sort(), offer.enabled(), offer.demand());
        }
        reload();
    }

    public static void reload() {
        Map<String, ServiceOffer> loaded = new ConcurrentHashMap<>();
        for (ServiceOffer offer : Database.query("SELECT " + COLUMNS + " FROM market_services", Services::map)) {
            if (offer != null) loaded.put(offer.key(), offer);
        }
        offers.keySet().retainAll(loaded.keySet());
        offers.putAll(loaded);
    }

    private static @Nullable ServiceOffer map(ResultSet row) throws SQLException {
        Material icon = Material.matchMaterial(row.getString("icon"));
        TaxClass taxClass = TaxClass.parse(row.getString("tax_class"));
        ServiceOffer.Kind kind;
        try {
            kind = ServiceOffer.Kind.valueOf(row.getString("kind"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new ServiceOffer(row.getString("key"), kind, row.getString("display_name"),
                icon == null ? Material.BARRIER : icon, row.getInt("base_price"), row.getDouble("elasticity"),
                taxClass == null ? TaxClass.STANDARD : taxClass, row.getString("effect"), row.getInt("amplifier"),
                row.getInt("duration_seconds"), row.getInt("max_seconds"), row.getInt("sort"), row.getBoolean("enabled"),
                row.getDouble("demand"));
    }

    public static List<ServiceOffer> all() {
        return offers.values().stream().filter(ServiceOffer::enabled).sorted(Comparator.comparingInt(ServiceOffer::sort)).toList();
    }

    public static @Nullable ServiceOffer get(@NotNull String key) {
        return offers.get(key);
    }

    public static boolean update(@NotNull String key, @NotNull String column, @Nullable Object value) {
        if (!EDITABLE_COLUMNS.containsValue(column)) throw new IllegalArgumentException("not editable: " + column);
        return Database.update("UPDATE market_services SET " + column + " = ? WHERE key = ?", value, key) > 0;
    }

    static void decayDemand(double factor) {
        Database.update("UPDATE market_services SET demand = CASE WHEN abs(demand * ?) < 0.05 THEN 0 ELSE demand * ? END WHERE demand <> 0",
                factor, factor);
    }


    /* purchase (blocking) */

    /**
     * Charges the service: net price (for a repair: of the given damage) plus tax, price check against what the player
     * saw, demand +1, trade log and treasury booking in one transaction.
     */
    public static Purchase buy(@NotNull UUID player, @NotNull String key, int damage, int expectedNet) {
        Purchase purchase = Database.inTransaction(connection -> {
            ServiceOffer offer = Database.queryOne(connection, "SELECT " + COLUMNS + " FROM market_services WHERE key = ? FOR UPDATE",
                    Services::map, key);
            if (offer == null || !offer.enabled()) return new Purchase(MarketRepository.Outcome.UNAVAILABLE, 0, 0, offer);
            int net = offer.kind() == ServiceOffer.Kind.REPAIR ? offer.repairPrice(damage) : offer.price();
            int tax = Taxes.taxOn(net, offer.taxClass());
            if (net != expectedNet) return new Purchase(MarketRepository.Outcome.PRICE_CHANGED, net, tax, offer);
            if (Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ? AND money >= ?",
                    net + tax, player, net + tax) == 0) {
                return new Purchase(MarketRepository.Outcome.INSUFFICIENT_FUNDS, net, tax, offer);
            }
            Database.update(connection, "UPDATE market_services SET demand = demand + 1 WHERE key = ?", key);
            Database.update(connection, "INSERT INTO market_transactions (player_uuid, material, kind, quantity, net, tax) VALUES (?, ?, 'BUY', 1, ?, ?)",
                    player, "SERVICE:" + key, net, tax);
            Treasury.deposit(connection, Treasury.Source.TRADE_TAX, tax, player);
            return new Purchase(MarketRepository.Outcome.OK, net, tax, withDemand(offer, offer.demand() + 1));
        });
        if (purchase.outcome() == MarketRepository.Outcome.OK) {
            Treasury.committed(purchase.tax());
            offers.put(key, Objects.requireNonNull(purchase.offer()));
        } else if (purchase.offer() != null) {
            offers.put(key, purchase.offer());
        }
        return purchase;
    }

    /** Pays a purchase back that could not be applied anymore (money and tax). */
    public static void refund(@NotNull UUID player, @NotNull Purchase purchase) {
        Database.inTransaction((Connection connection) -> {
            Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", purchase.total(), player);
            if (purchase.offer() != null) {
                // the purchase leaves the statement, the money came back
                Database.update(connection, "UPDATE market_transactions SET moves_money = FALSE WHERE id = (SELECT id FROM market_transactions "
                        + "WHERE player_uuid = ? AND material = ? AND kind = 'BUY' AND moves_money ORDER BY id DESC LIMIT 1)",
                        player, "SERVICE:" + purchase.offer().key());
            }
            if (purchase.tax() > 0) {
                Database.update(connection, "INSERT INTO treasury_ledger (source, amount, player_uuid) VALUES (?, ?, ?)",
                        Treasury.Source.TRADE_TAX.name(), -purchase.tax(), player);
            }
            return null;
        });
        Treasury.committed(-purchase.tax());
    }

    private static ServiceOffer withDemand(ServiceOffer offer, double demand) {
        return new ServiceOffer(offer.key(), offer.kind(), offer.displayName(), offer.icon(), offer.basePrice(), offer.elasticity(),
                offer.taxClass(), offer.effect(), offer.amplifier(), offer.durationSeconds(), offer.maxSeconds(), offer.sort(),
                offer.enabled(), demand);
    }


    /* in the game (main thread) */

    public static @Nullable PotionEffectType effectType(@NotNull ServiceOffer offer) {
        return offer.effect() == null ? null : Registry.MOB_EFFECT.get(NamespacedKey.minecraft(offer.effect()));
    }

    /** Why the service cannot be bought right now, or null if it can. */
    public static @Nullable String unavailableReason(@NotNull Player player, @NotNull ServiceOffer offer) {
        World world = player.getWorld();
        return switch (offer.kind()) {
            case EFFECT -> {
                PotionEffectType type = effectType(offer);
                if (type == null) yield "Gerade nicht verfügbar";
                PotionEffect current = player.getPotionEffect(type);
                if (current != null && (current.isInfinite() || current.getDuration() >= offer.maxSeconds() * 20 - 20 * 60)) {
                    yield "Du hast schon die maximale Dauer";
                }
                yield null;
            }
            case REPAIR -> repairDamage(player.getInventory().getItemInMainHand()) > 0 ? null
                    : "Nimm ein beschädigtes Item in die Hand";
            case WEATHER -> world.getEnvironment() != World.Environment.NORMAL ? "Nur in der Oberwelt"
                    : !world.hasStorm() && !world.isThundering() ? "Es scheint bereits die Sonne" : null;
            case DAY -> world.getEnvironment() != World.Environment.NORMAL ? "Nur in der Oberwelt"
                    : isDay(world) ? "Es ist schon Tag" : null;
        };
    }

    /** Damage points of a repairable item, 0 if it cannot be repaired here (undamaged, no durability, plugin items). */
    public static int repairDamage(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable) || !damageable.hasDamage()) return 0;
        // custom items (bedrock breaker, flamethrower, ...) keep their durability as part of their balance
        String pluginNamespace = new NamespacedKey(Main.getInstance(), "custom").getNamespace();
        if (meta.getPersistentDataContainer().getKeys().stream().anyMatch(key -> key.getNamespace().equals(pluginNamespace) && !Goods.isMark(key))) {
            return 0;
        }
        return damageable.getDamage();
    }

    private static boolean isDay(World world) {
        long time = world.getTime() % 24_000;
        return time < 12_300 || time > 23_850;
    }

    /** Applies a paid service; false if it cannot be applied anymore (the caller refunds). */
    public static boolean apply(@NotNull Player player, @NotNull ServiceOffer offer, @Nullable ItemStack repairTarget) {
        if (!player.isOnline()) return false;
        World world = player.getWorld();
        switch (offer.kind()) {
            case EFFECT -> {
                PotionEffectType type = effectType(offer);
                if (type == null) return false;
                PotionEffect current = player.getPotionEffect(type);
                int remaining = current == null || current.isInfinite() ? 0 : current.getDuration();
                int ticks = Math.min(remaining + offer.durationSeconds() * 20, offer.maxSeconds() * 20);
                int amplifier = current == null ? offer.amplifier() : Math.max(current.getAmplifier(), offer.amplifier());
                player.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, false, true));
            }
            case REPAIR -> {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (repairTarget == null || !hand.equals(repairTarget)) return false;
                ItemMeta meta = hand.getItemMeta();
                if (!(meta instanceof Damageable damageable)) return false;
                damageable.setDamage(0);
                hand.setItemMeta(meta);
            }
            case WEATHER -> {
                if (world.getEnvironment() != World.Environment.NORMAL) return false;
                world.setStorm(false);
                world.setThundering(false);
                world.setClearWeatherDuration(Math.max(20 * 60, offer.durationSeconds() * 20));
                Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§e☀ §f" + player.getName() + " §fhat die §eSonne §fgekauft!"));
            }
            case DAY -> {
                if (world.getEnvironment() != World.Environment.NORMAL) return false;
                world.setTime(1_000);
                Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§e☀ §f" + player.getName() + " §fhat den §eTag §fgekauft!"));
            }
        }
        return true;
    }

    public static Component name(@NotNull ServiceOffer offer) {
        Component name = Text.of(offer.displayName());
        return name == null ? Component.text(offer.key()) : name;
    }

}
