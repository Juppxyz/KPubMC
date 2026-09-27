package xyz.jupp.minecraft.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.utils.EndAccess;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.utils.Text;

import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Nomad, the team point dealer: three team contracts that run three days each (one is renewed every day, the third
 * slot is always a special contract), an open redemption list with two "hot" items per day (double points) and a
 * weekly race of the delivered points with a bonus for the top three teams.
 * Every method that touches the database is blocking.
 */
public final class Nomad {

    private Nomad() {}

    public static final String PREFIX = "§6Nomad §7» §f";
    private static final int CONTRACT_DAYS = 3;
    private static final int SPECIAL_SLOT = 2;
    private static final int HOT_ITEMS = 2;
    // normal contracts: goods worth this many Schilling, 1 team point per POINT_VALUE Schilling
    private static final int MIN_CONTRACT_VALUE = 1_000;
    private static final int MAX_CONTRACT_VALUE = 4_000;
    private static final int POINT_VALUE = 5;
    // a material is not asked for again within this many days (as long as there are alternatives)
    private static final int REPEAT_DAYS = 30;
    // villager currency: trading halls make it in masses, so it is never worth team points
    private static final Set<Material> NEVER_WANTED = EnumSet.of(Material.EMERALD, Material.EMERALD_BLOCK);
    public static final int[] RACE_PRIZES = {1_000, 500, 250};

    public record Contract(long id, int slot, Material material, int required, int reward, Instant endsAt) {}

    public record Progress(int delivered, boolean completed) {}

    public record Redeemable(Material material, int points) {}

    public record RaceEntry(String teamID, long points) {}

    public enum Outcome { OK, NOT_ACTIVE, ALREADY_DONE, NO_TEAM }

    public record Delivery(Outcome outcome, int accepted, int points, boolean completed, int delivered) {}

    private record Special(Material material, int amount, int reward) {}

    // items that need an adventure (structures, bosses, rare mobs); plain items only, so nothing with variants or damage
    private static final List<Special> SPECIALS = List.of(
            new Special(Material.TOTEM_OF_UNDYING, 1, 300),
            new Special(Material.HEART_OF_THE_SEA, 1, 450),
            new Special(Material.ECHO_SHARD, 8, 500),
            new Special(Material.NAUTILUS_SHELL, 3, 300),
            new Special(Material.WITHER_SKELETON_SKULL, 2, 600),
            new Special(Material.BREEZE_ROD, 16, 400),
            new Special(Material.PHANTOM_MEMBRANE, 16, 250),
            new Special(Material.GHAST_TEAR, 8, 350),
            new Special(Material.SNIFFER_EGG, 1, 500),
            new Special(Material.HEAVY_CORE, 1, 800),
            new Special(Material.ENCHANTED_GOLDEN_APPLE, 1, 600),
            new Special(Material.MUSIC_DISC_PIGSTEP, 1, 500),
            new Special(Material.MUSIC_DISC_OTHERSIDE, 1, 450),
            new Special(Material.MUSIC_DISC_RELIC, 1, 400),
            new Special(Material.SADDLE, 2, 250),
            new Special(Material.NAME_TAG, 3, 300),
            new Special(Material.EXPERIENCE_BOTTLE, 32, 300),
            new Special(Material.RABBIT_FOOT, 6, 250),
            new Special(Material.TURTLE_SCUTE, 8, 350),
            new Special(Material.ARMADILLO_SCUTE, 8, 300),
            new Special(Material.DIAMOND_HORSE_ARMOR, 1, 300),
            new Special(Material.TRIAL_KEY, 4, 300),
            new Special(Material.OMINOUS_TRIAL_KEY, 1, 500),
            new Special(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, 1, 300),
            new Special(Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE, 1, 400),
            new Special(Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, 1, 600),
            new Special(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 500),
            new Special(Material.ANCIENT_DEBRIS, 4, 500),
            new Special(Material.ARCHER_POTTERY_SHERD, 2, 300),
            new Special(Material.CREEPER_HEAD, 1, 450),
            new Special(Material.ZOMBIE_HEAD, 1, 350),
            new Special(Material.SKELETON_SKULL, 1, 350),
            new Special(Material.WET_SPONGE, 4, 300),
            new Special(Material.PRISMARINE_CRYSTALS, 16, 250),
            new Special(Material.BELL, 1, 300),
            new Special(Material.CONDUIT, 1, 700),
            // only while the End is open
            new Special(Material.SHULKER_SHELL, 4, 400),
            new Special(Material.DRAGON_BREATH, 8, 300));

    // The redemption list, it decides over the table (no admin command yet): changed points are taken over, items that
    // are no longer listed are switched off. Contracts are the main source of team points, so the list pays little.
    private static final Map<Material, Integer> DEFAULT_REDEEMABLES = Map.ofEntries(
            // complex and rare: crafted from rare parts
            Map.entry(Material.BEACON, 275),
            Map.entry(Material.MUSIC_DISC_5, 200),
            Map.entry(Material.NETHERITE_BLOCK, 200),
            Map.entry(Material.MACE, 150),
            Map.entry(Material.RECOVERY_COMPASS, 110),
            Map.entry(Material.NETHER_STAR, 250),
            Map.entry(Material.ENCHANTED_GOLDEN_APPLE, 90),
            Map.entry(Material.MUSIC_DISC_PIGSTEP, 60),
            Map.entry(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, 50),
            Map.entry(Material.ELYTRA, 45),
            Map.entry(Material.BUDDING_AMETHYST, 30),
            Map.entry(Material.HEART_OF_THE_SEA, 20),
            Map.entry(Material.DISC_FRAGMENT_5, 20),
            Map.entry(Material.ECHO_SHARD, 12),
            Map.entry(Material.TOTEM_OF_UNDYING, 10),
            Map.entry(Material.NAUTILUS_SHELL, 8),
            Map.entry(Material.SHULKER_SHELL, 6),
            Map.entry(Material.SPONGE, 4));

    private static volatile List<Contract> contracts = List.of();
    private static volatile List<Redeemable> redeemables = List.of();
    private static volatile Set<Material> hot = Set.of();
    private static volatile LocalDate hotDay = null;


    /* lifecycle */

    public static void load() {
        for (Map.Entry<Material, Integer> entry : DEFAULT_REDEEMABLES.entrySet()) {
            Database.update("INSERT INTO nomad_redeemables (material, points, enabled) VALUES (?, ?, TRUE) "
                            + "ON CONFLICT (material) DO UPDATE SET points = EXCLUDED.points, enabled = TRUE",
                    entry.getKey().name(), entry.getValue());
        }
        Object[] listed = DEFAULT_REDEEMABLES.keySet().stream().map(Material::name).toArray();
        String placeholders = String.join(", ", java.util.Collections.nCopies(listed.length, "?"));
        Database.update("UPDATE nomad_redeemables SET enabled = FALSE WHERE material NOT IN (" + placeholders + ")", listed);
        dailyUpdate(LocalDate.now(Market.ZONE));
    }

    public static boolean isUpToDate(@NotNull LocalDate today) {
        return today.equals(hotDay) && contracts.size() == 3 && contracts.stream().allMatch(c -> c.endsAt().isAfter(Instant.now()));
    }

    /**
     * Renews expired contracts, draws today's hot items and pays out last week's race (each only once).
     * Returns the announcements for the chat.
     */
    public static List<Component> dailyUpdate(@NotNull LocalDate today) {
        List<Component> announcements = new ArrayList<>();
        reload();

        Set<Material> used = new HashSet<>();
        Map<Integer, Contract> bySlot = new HashMap<>();
        for (Contract contract : contracts) {
            bySlot.put(contract.slot(), contract);
            used.add(contract.material());
        }
        // the very first contracts are staggered, so one of them is renewed every day
        boolean firstRun = contracts.isEmpty();
        for (int slot = 0; slot < 3; slot++) {
            if (bySlot.containsKey(slot)) continue;
            int days = firstRun ? slot + 1 : CONTRACT_DAYS;
            Contract created = createContract(slot, today.plusDays(days), used);
            if (created == null) continue;
            bySlot.put(slot, created);
            used.add(created.material());
            announcements.add(Text.section(PREFIX + "Neuer Auftrag: §e" + created.required() + "× ")
                    .append(Component.translatable(created.material().translationKey()))
                    .append(Text.section(" §8(§a+" + created.reward() + " Team-Punkte§8)")));
        }

        if (!today.equals(hotDay)) drawHotItems(today);
        announcements.addAll(payOutRace(today));
        reload();
        return announcements;
    }

    private static void reload() {
        contracts = Database.query("SELECT * FROM nomad_contracts WHERE ends_at > now() ORDER BY slot", row -> {
            Material material = Material.matchMaterial(row.getString("material"));
            return material == null ? null : new Contract(row.getLong("id"), row.getInt("slot"), material,
                    row.getInt("required"), row.getInt("reward"), row.getTimestamp("ends_at").toInstant());
        }).stream().filter(Objects::nonNull).toList();
        redeemables = Database.query("SELECT material, points FROM nomad_redeemables WHERE enabled", row -> {
            Material material = Material.matchMaterial(row.getString(1));
            return material == null || NEVER_WANTED.contains(material) ? null : new Redeemable(material, row.getInt(2));
        }).stream().filter(Objects::nonNull).sorted(Comparator.comparingInt(Redeemable::points).reversed()).toList();
        LocalDate today = LocalDate.now(Market.ZONE);
        List<Material> todaysHot = Database.query("SELECT material FROM nomad_hot WHERE day = ?",
                row -> Material.matchMaterial(row.getString(1)), Date.valueOf(today));
        if (!todaysHot.isEmpty()) {
            hot = Set.copyOf(todaysHot.stream().filter(Objects::nonNull).toList());
            hotDay = today;
        }
    }

    private static @Nullable Contract createContract(int slot, LocalDate endDay, Set<Material> used) {
        Material material;
        int required;
        int reward;
        Map<Material, Instant> recent = recentlyAsked();
        if (slot == SPECIAL_SLOT) {
            List<Special> candidates = freshest(SPECIALS.stream()
                    .filter(special -> !used.contains(special.material()) && EndAccess.isAvailable(special.material())).toList(),
                    Special::material, recent);
            if (candidates.isEmpty()) return null;
            Special special = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            material = special.material();
            required = special.amount();
            reward = special.reward();
        } else {
            List<MarketItem> candidates = Market.all().stream()
                    .filter(item -> item.enabled() && item.core() && item.category() != Category.RARE)
                    .filter(item -> item.material().isItem() && item.material().getMaxStackSize() > 1)
                    .filter(item -> !item.material().name().endsWith("_SPAWN_EGG") && !used.contains(item.material()))
                    .filter(item -> !NEVER_WANTED.contains(item.material()))
                    .filter(item -> EndAccess.isAvailable(item.material()))
                    .toList();
            candidates = freshest(candidates, MarketItem::material, recent);
            if (candidates.isEmpty()) return null;
            MarketItem item = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            double unitValue = (double) item.basePrice() / item.amount();
            int targetValue = ThreadLocalRandom.current().nextInt(MIN_CONTRACT_VALUE, MAX_CONTRACT_VALUE + 1);
            material = item.material();
            required = niceAmount(targetValue / unitValue);
            reward = (int) Math.max(50, Math.round(required * unitValue / POINT_VALUE / 10.0) * 10);
        }
        Instant endsAt = endDay.atStartOfDay(Market.ZONE).toInstant();
        Long id = Database.queryOne("INSERT INTO nomad_contracts (slot, material, required, reward, ends_at) VALUES (?, ?, ?, ?, ?) RETURNING id",
                row -> row.getLong(1), slot, material.name(), required, reward, Timestamp.from(endsAt));
        Main.getInstance().getSLF4JLogger().info("nomad contract {}: {}x {} for {} team points until {}", slot, required, material, reward, endDay);
        return new Contract(Objects.requireNonNull(id), slot, material, required, reward, endsAt);
    }

    // material -> last time it was asked for, within the repeat window
    private static Map<Material, Instant> recentlyAsked() {
        Map<Material, Instant> recent = new HashMap<>();
        Database.query("SELECT material, MAX(created_at) FROM nomad_contracts WHERE created_at >= now() - make_interval(days => ?) GROUP BY material",
                row -> {
                    Material material = Material.matchMaterial(row.getString(1));
                    if (material != null) recent.put(material, row.getTimestamp(2).toInstant());
                    return null;
                }, REPEAT_DAYS);
        return recent;
    }

    /** The candidates not asked for within the repeat window; if there are none, the one asked for longest ago. */
    static <T> List<T> freshest(List<T> candidates, java.util.function.Function<T, Material> material, Map<Material, Instant> recent) {
        List<T> fresh = candidates.stream().filter(candidate -> !recent.containsKey(material.apply(candidate))).toList();
        if (!fresh.isEmpty() || candidates.isEmpty()) return fresh;
        return List.of(candidates.stream().min(Comparator.comparing(candidate -> recent.get(material.apply(candidate)))).orElseThrow());
    }

    // round to amounts that feel natural: 1..7, multiples of 4, 16 or 64
    static int niceAmount(double amount) {
        if (amount >= 128) return (int) Math.min(1024, Math.round(amount / 64) * 64);
        if (amount >= 32) return (int) Math.round(amount / 16) * 16;
        if (amount >= 8) return (int) Math.round(amount / 4) * 4;
        return (int) Math.max(1, Math.round(amount));
    }

    private static void drawHotItems(LocalDate today) {
        List<Redeemable> pool = new ArrayList<>(redeemables.stream().filter(r -> EndAccess.isAvailable(r.material())).toList());
        java.util.Collections.shuffle(pool, ThreadLocalRandom.current());
        for (Redeemable redeemable : pool.subList(0, Math.min(HOT_ITEMS, pool.size()))) {
            Database.update("INSERT INTO nomad_hot (day, material) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    Date.valueOf(today), redeemable.material().name());
        }
    }


    /* cache access (any thread) */

    public static List<Contract> contracts() {
        return contracts;
    }

    public static @Nullable Contract contract(long id) {
        return contracts.stream().filter(contract -> contract.id() == id).findFirst().orElse(null);
    }

    public static List<Redeemable> redeemables() {
        return redeemables;
    }

    public static boolean isHot(@NotNull Material material) {
        return hot.contains(material);
    }

    /** Points per item, doubled for today's hot items; 0 if Nomad does not take it. */
    public static int pointsFor(@NotNull Material material) {
        for (Redeemable redeemable : redeemables) {
            if (redeemable.material() == material) return redeemable.points() * (isHot(material) ? 2 : 1);
        }
        return 0;
    }


    /* team state (blocking) */

    public static Map<Long, Progress> progress(@NotNull String teamID) {
        Map<Long, Progress> result = new HashMap<>();
        Database.query("SELECT contract_id, delivered, completed FROM nomad_progress WHERE team_id = ?", row -> {
            result.put(row.getLong(1), new Progress(row.getInt(2), row.getBoolean(3)));
            return null;
        }, teamID);
        return result;
    }

    /** Delivers up to {@code offered} items to a contract; the caller gives back what was not accepted. */
    public static Delivery deliver(@NotNull UUID player, @NotNull String teamID, long contractID, int offered) {
        Delivery delivery = Database.inTransaction(connection -> {
            Contract contract = Database.queryOne(connection, "SELECT * FROM nomad_contracts WHERE id = ? AND ends_at > now()", row ->
                    new Contract(row.getLong("id"), row.getInt("slot"), Objects.requireNonNull(Material.matchMaterial(row.getString("material"))),
                            row.getInt("required"), row.getInt("reward"), row.getTimestamp("ends_at").toInstant()), contractID);
            if (contract == null) return new Delivery(Outcome.NOT_ACTIVE, 0, 0, false, 0);
            if (Database.queryOne(connection, "SELECT 1 FROM teams WHERE team_id = ?", row -> 1, teamID) == null) {
                return new Delivery(Outcome.NO_TEAM, 0, 0, false, 0);
            }
            Database.update(connection, "INSERT INTO nomad_progress (contract_id, team_id) VALUES (?, ?) ON CONFLICT DO NOTHING", contractID, teamID);
            Progress progress = Database.queryOne(connection, "SELECT delivered, completed FROM nomad_progress WHERE contract_id = ? AND team_id = ? FOR UPDATE",
                    row -> new Progress(row.getInt(1), row.getBoolean(2)), contractID, teamID);
            if (progress == null || progress.completed()) return new Delivery(Outcome.ALREADY_DONE, 0, 0, true, contract.required());

            int accepted = Math.max(0, Math.min(offered, contract.required() - progress.delivered()));
            int delivered = progress.delivered() + accepted;
            boolean completed = delivered >= contract.required();
            Database.update(connection, "UPDATE nomad_progress SET delivered = ?, completed = ? WHERE contract_id = ? AND team_id = ?",
                    delivered, completed, contractID, teamID);
            int points = completed ? contract.reward() : 0;
            if (points > 0) Database.update(connection, "UPDATE teams SET points = points + ? WHERE team_id = ?", points, teamID);
            logDelivery(connection, teamID, player, "CONTRACT", contract.material(), accepted, points);
            return new Delivery(Outcome.OK, accepted, points, completed, delivered);
        });
        if (delivery.points() > 0) {
            Main.getInstance().getSLF4JLogger().info("teamPoints {} +{} (nomad contract {})", teamID, delivery.points(), contractID);
        }
        return delivery;
    }

    /** Redeems items from the open list; returns the points (0 if not taken or no team). */
    public static int redeem(@NotNull UUID player, @NotNull String teamID, @NotNull Material material, int quantity) {
        int points = pointsFor(material) * quantity;
        if (points <= 0) return 0;
        boolean booked = Database.inTransaction(connection -> {
            if (Database.update(connection, "UPDATE teams SET points = points + ? WHERE team_id = ?", points, teamID) == 0) return false;
            logDelivery(connection, teamID, player, "REDEEM", material, quantity, points);
            return true;
        });
        if (!booked) return 0;
        Main.getInstance().getSLF4JLogger().info("teamPoints {} +{} (nomad redeem {}x {})", teamID, points, quantity, material);
        return points;
    }

    private static void logDelivery(Connection connection, String teamID, @Nullable UUID player, String kind,
                                    @Nullable Material material, int quantity, int points) throws SQLException {
        Database.update(connection, "INSERT INTO nomad_deliveries (team_id, player_uuid, kind, material, quantity, points) VALUES (?, ?, ?, ?, ?, ?)",
                teamID, player, kind, material == null ? null : material.name(), quantity, points);
    }


    /* weekly race */

    public static LocalDate weekStart(@NotNull LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static Instant weekEnd() {
        return weekStart(LocalDate.now(Market.ZONE)).plusWeeks(1).atStartOfDay(Market.ZONE).toInstant();
    }

    /** Points per team delivered in the week that starts on the given Monday, best first. */
    public static List<RaceEntry> standings(@NotNull LocalDate weekStart) {
        return Database.query("""
                SELECT team_id, SUM(points) FROM nomad_deliveries
                WHERE kind IN ('CONTRACT', 'REDEEM') AND created_at >= ? AND created_at < ?
                GROUP BY team_id HAVING SUM(points) > 0 ORDER BY SUM(points) DESC""",
                row -> new RaceEntry(row.getString(1), row.getLong(2)),
                Timestamp.from(weekStart.atStartOfDay(Market.ZONE).toInstant()),
                Timestamp.from(weekStart.plusWeeks(1).atStartOfDay(Market.ZONE).toInstant()));
    }

    // pays the bonus of the finished week once (the payout row guards against paying twice)
    private static List<Component> payOutRace(LocalDate today) {
        LocalDate lastWeek = weekStart(today).minusWeeks(1);
        String key = lastWeek.get(IsoFields.WEEK_BASED_YEAR) + "-W" + lastWeek.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        List<RaceEntry> winners = standings(lastWeek).stream().limit(RACE_PRIZES.length).toList();
        Boolean paid = Database.inTransaction(connection -> {
            if (Database.update(connection, "INSERT INTO nomad_race_payouts (week) VALUES (?) ON CONFLICT DO NOTHING", key) == 0) return false;
            for (int place = 0; place < winners.size(); place++) {
                RaceEntry winner = winners.get(place);
                Database.update(connection, "UPDATE teams SET points = points + ? WHERE team_id = ?", RACE_PRIZES[place], winner.teamID());
                logDelivery(connection, winner.teamID(), null, "RACE", null, 0, RACE_PRIZES[place]);
            }
            return true;
        });
        if (!Boolean.TRUE.equals(paid) || winners.isEmpty()) return List.of();

        List<Component> announcements = new ArrayList<>();
        announcements.add(Text.section(PREFIX + "§6Das Wochen-Rennen ist vorbei!"));
        for (int place = 0; place < winners.size(); place++) {
            announcements.add(Text.section("§6" + (place + 1) + ". §f" + teamName(winners.get(place).teamID())
                    + " §8(" + winners.get(place).points() + " Punkte) §a+" + RACE_PRIZES[place] + " Bonus"));
        }
        return announcements;
    }

    public static String teamName(@NotNull String teamID) {
        TeamCacheObject team = CacheHandler.getInstance().getTeamCacheObject(teamID);
        return team == null ? "§7Unbekanntes Team" : team.getTeamColor() + team.getTeamName();
    }

}
