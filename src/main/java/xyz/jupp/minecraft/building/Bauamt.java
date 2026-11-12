package xyz.jupp.minecraft.building;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.economy.Bank;
import xyz.jupp.minecraft.economy.Bankruptcy;
import xyz.jupp.minecraft.economy.Bonds;
import xyz.jupp.minecraft.economy.Loans;
import xyz.jupp.minecraft.economy.Treasury;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The Bauamt: the state pays players for copies of its building templates. A player takes an order for a template at
 * the Bauamt villager, builds the copy somewhere; the plugin notices the finished copy when its last block is placed
 * (the player gets a hint) and the player claims the grant at the villager, where the copy is checked again and paid
 * out of the treasury. The reward of a template is a guide value: the treasury pays it in full while it holds at least
 * its average daily income of the last week, below that proportionally less, and it never lets a grant take it under
 * half of that income (nothing at all while the state is broke).
 * Paid copies are state property: whoever breaks a piece of one owes a flat fee plus a sum per block, due within days
 * at the Bauamt, otherwise they are wanted. Builds are also inspected now and then, only in loaded chunks, for damage
 * nobody was seen doing (explosions, fire) and for repairs.
 */
public final class Bauamt {

    private Bauamt() {}

    /** The treasury is compared with its average daily income over these days. */
    static final int INCOME_DAYS = 7;
    /** The treasury keeps at least this share of its average daily income, a grant never takes it below. */
    static final double RESERVE_SHARE = 0.5;
    /**
     * The grants of 24 hours take this share of the average daily income (the daily budget): one grant is never more than
     * a whole budget, and none is paid once it is used up (the last one may go over it).
     */
    static final double DAILY_BUDGET_SHARE = 0.5;
    private static final String GRANTED_DAY = "SELECT -COALESCE(SUM(amount), 0) FROM treasury_ledger "
            + "WHERE source = 'BUILDING_GRANT' AND created_at >= now() - interval '24 hours'";
    /** Damage to state property: a flat fee per incident plus a sum per block, due within days, otherwise wanted. */
    public static final int DAMAGE_FLAT = 1000;
    public static final int DAMAGE_PER_BLOCK = 250;
    public static final int DAMAGE_DUE_DAYS = 3;
    public static final String DAMAGE_REASON = "Zerstörung von Staatseigentum";
    /** Open orders a player may hold per template. */
    public static final int ORDERS_PER_TEMPLATE = 5;
    static final int ORDER_EXPIRES_DAYS = 14;
    // intact share below which a copy counts as damaged, and from which a damaged one counts as repaired
    static final double DAMAGED_BELOW = 0.80;
    static final double REPAIRED_AT = 0.98;
    static final int GONE_AFTER_DAYS = 7;
    static final int PENDING_EXPIRES_DAYS = 14;
    static final int INSPECT_PER_RUN = 20;
    static final long INSPECT_PERIOD_TICKS = 20L * 60 * 5;
    static final long NOTICE_COOLDOWN_MILLIS = 10_000;
    static final long ORDER_HINT_COOLDOWN_MILLIS = 60_000;

    private static volatile List<Blueprint> templates = List.of();
    private static volatile Set<Material> triggers = Set.of();
    private static final List<Build> builds = new CopyOnWriteArrayList<>();
    private static final List<Order> orders = new CopyOnWriteArrayList<>();
    private static final List<Damage> damages = new CopyOnWriteArrayList<>();
    // the state's builds by the chunks their box touches, for the block break check
    private static volatile Map<String, Map<Long, List<Build>>> index = Map.of();
    private static volatile Funding funding = new Funding(0, 0, 1.0, false, 0, 0, 0, 0);
    // when the treasury booked for the first time (the average income of a young season divides by its real age)
    private static volatile @Nullable Instant firstIncome;
    private static final Object PAY_LOCK = new Object();
    private static final Map<UUID, Long> orderHints = new ConcurrentHashMap<>();
    private static int cursor;


    /* loading */

    /** Blocking, in onEnable after the treasury. */
    public static void load() {
        reloadTemplates();
        builds.clear();
        builds.addAll(Database.query("SELECT * FROM bauamt_builds WHERE state <> 'GONE' ORDER BY id", row -> new Build(
                row.getLong("id"), row.getInt("template_id"), row.getObject("player_uuid", UUID.class), row.getString("player_name"),
                row.getString("world"), row.getInt("x"), row.getInt("y"), row.getInt("z"),
                row.getInt("size_x"), row.getInt("size_y"), row.getInt("size_z"),
                StructureRotation.valueOf(row.getString("rotation")), row.getBoolean("mirrored"),
                Build.State.valueOf(row.getString("state")), row.getInt("reward"),
                row.getTimestamp("created_at").toInstant(), instant(row.getTimestamp("paid_at")), instant(row.getTimestamp("damaged_at")))));
        orders.clear();
        orders.addAll(Database.query("SELECT * FROM bauamt_orders WHERE build_id IS NULL ORDER BY id", row -> new Order(
                row.getLong("id"), row.getObject("player_uuid", UUID.class), row.getInt("template_id"), row.getTimestamp("taken_at").toInstant())));
        damages.clear();
        damages.addAll(Database.query("SELECT * FROM bauamt_damage WHERE paid_at IS NULL ORDER BY id", row -> new Damage(
                row.getLong("id"), row.getLong("build_id"), row.getObject("player_uuid", UUID.class), row.getString("player_name"),
                row.getInt("blocks"), row.getInt("amount"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("due_at").toInstant(),
                row.getTimestamp("wanted_at") != null, row.getTimestamp("served_at") != null, row.getBoolean("warrant_shared"))));
        rebuildIndex();
        refreshFunding();
        Bauplan.load();
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** Blocking: the templates from the database; one with a block this version does not know is skipped. */
    static void reloadTemplates() {
        List<Blueprint> loaded = new ArrayList<>();
        Database.query("SELECT * FROM bauamt_templates ORDER BY id", row -> {
            String name = row.getString("name");
            try {
                Blueprint.Contents contents = Blueprint.parse(row.getString("blocks"));
                loaded.add(new Blueprint(row.getInt("id"), name, row.getInt("reward"), row.getBoolean("enabled"), row.getString("world"),
                        row.getInt("origin_x"), row.getInt("origin_y"), row.getInt("origin_z"),
                        row.getInt("size_x"), row.getInt("size_y"), row.getInt("size_z"), contents.blocks(), contents.props()));
            } catch (IllegalArgumentException e) {
                Main.getInstance().getSLF4JLogger().warn("Bauamt template {} skipped: {}", name, e.getMessage());
            }
            return null;
        });
        Set<Material> materials = new HashSet<>();
        for (Blueprint template : loaded) {
            if (template.enabled()) materials.addAll(template.materials().keySet());
        }
        templates = List.copyOf(loaded);
        triggers = Set.copyOf(materials);
    }

    public static @NotNull List<Blueprint> templates() {
        return templates;
    }

    public static @NotNull List<Blueprint> enabledTemplates() {
        return templates.stream().filter(Blueprint::enabled).toList();
    }

    public static @Nullable Blueprint template(int id) {
        for (Blueprint template : templates) {
            if (template.id() == id) return template;
        }
        return null;
    }

    public static @Nullable Blueprint template(@NotNull String name) {
        for (Blueprint template : templates) {
            if (template.name().equalsIgnoreCase(name)) return template;
        }
        return null;
    }

    public static @NotNull List<Build> builds() {
        return builds;
    }

    public static @Nullable Build build(long id) {
        for (Build build : builds) {
            if (build.id() == id) return build;
        }
        return null;
    }

    /** Paid copies of a template (the admin's model does not count). */
    public static int copies(@NotNull Blueprint template) {
        int count = 0;
        for (Build build : builds) {
            if (build.templateId() == template.id() && build.paidAt() != null) count++;
        }
        return count;
    }

    static boolean isTrigger(@NotNull Material material) {
        return triggers.contains(material);
    }

    /** "12 × 3 × 5" */
    public static @NotNull String size(@NotNull Blueprint template) {
        return String.format(Locale.ROOT, "%d × %d × %d", template.sizeX(), template.sizeY(), template.sizeZ());
    }


    /* what the treasury pays */

    /**
     * The treasury's state for the grants: its balance, its average daily income, the share of a reward it pays right
     * now, the reserve it keeps, and what is really free (not owed to savers and bond holders). Share and reserve look at
     * the free money: deposits and bonds in the balance belong to the players.
     */
    public record Funding(long balance, long averageIncome, double share, boolean broke, long free, long brokeBelow, long promised,
                          long grantedDay) {
        public boolean reduced() {
            return share < 1.0;
        }

        public int percent() {
            return (int) Math.round(share * 100);
        }

        /**
         * What stays free in the treasury whatever happens: half a day's income, never so little that a grant makes the
         * state broke, plus the interest Basil promised to the savers.
         */
        public long reserve() {
            return reserveOf(averageIncome, brokeBelow, promised);
        }

        /** What the daily budget still allows within these 24 hours. */
        public long budgetLeft() {
            return budgetLeftOf(averageIncome, grantedDay);
        }
    }

    /** Cached; refreshed with every inspection, payout and menu. */
    public static @NotNull Funding funding() {
        return funding;
    }

    /** Blocking: reads the treasury and the income of the last days. */
    public static @NotNull Funding refreshFunding() {
        long balance = Treasury.balance();
        long income = Treasury.income(Treasury.inflowSince(Instant.now().minus(Duration.ofDays(INCOME_DAYS))));
        long average = Math.round(income / incomeDays());
        Bankruptcy.Level level = Bankruptcy.level();
        long free = level.free() - Bonds.owed();
        Long promised = Database.queryOne(PROMISED_INTEREST, row -> row.getLong(1));
        Long granted = Database.queryOne(GRANTED_DAY, row -> row.getLong(1));
        Funding current = new Funding(balance, average, share(free, average), Bankruptcy.isBroke(), free, level.brokeBelow(),
                promised == null ? 0 : promised, granted == null ? 0 : granted);
        funding = current;
        return current;
    }

    // the interest Basil promised on the running fixed deposits (paid from the free treasury when they end)
    private static final String PROMISED_INTEREST = "SELECT COALESCE(ROUND(SUM(amount * rate)), 0) FROM bank_deposits WHERE NOT paid_out";

    static long reserveOf(long averageIncome, long brokeBelow, long promised) {
        return Math.max(Math.round(averageIncome * RESERVE_SHARE), brokeBelow) + promised;
    }

    static long budgetLeftOf(long averageIncome, long grantedDay) {
        return Math.max(0, dailyBudget(averageIncome) - grantedDay);
    }

    // the days the average income is taken over: INCOME_DAYS, the real age while the season is younger (at least one)
    private static double incomeDays() {
        Instant first = firstIncome;
        if (first == null) {
            Timestamp stored = Database.queryOne("SELECT MIN(created_at) FROM treasury_ledger", row -> row.getTimestamp(1));
            if (stored == null) return 1;
            first = stored.toInstant();
            firstIncome = first;
        }
        double days = Duration.between(first, Instant.now()).toMillis() / 86_400_000.0;
        return Math.max(1.0, Math.min(INCOME_DAYS, days));
    }

    /** The full reward while the free treasury holds its average daily income, below that the same share less. */
    static double share(long free, long averageIncome) {
        if (averageIncome <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, (double) free / averageIncome));
    }

    /** What a copy of the template pays right now (0 while the state is broke). */
    public static int payout(@NotNull Blueprint template) {
        return payout(template, funding);
    }

    // the guide value times the share, never more than one day's budget: a big template is paid less, not never
    static int payout(@NotNull Blueprint template, @NotNull Funding funding) {
        if (funding.broke()) return 0;
        return (int) Math.min(Math.round(template.reward() * funding.share()), dailyBudget(funding.averageIncome()));
    }

    public static long dailyBudget(long averageIncome) {
        return Math.round(averageIncome * DAILY_BUDGET_SHARE);
    }

    /** Whether the treasury can pay this right now: it keeps its reserve, never spends what it owes, stays in the daily budget. */
    public static boolean affordable(int payout, @NotNull Funding funding) {
        return !funding.broke() && payout > 0 && funding.free() - payout >= funding.reserve() && funding.budgetLeft() > 0;
    }


    /* orders */

    /** The player's open orders. */
    public static @NotNull List<Order> orders(@NotNull UUID player) {
        List<Order> result = new ArrayList<>();
        for (Order order : orders) {
            if (order.player().equals(player)) result.add(order);
        }
        return result;
    }

    public static int openOrders(@NotNull UUID player, int templateId) {
        int count = 0;
        for (Order order : orders) {
            if (order.player().equals(player) && order.templateId() == templateId) count++;
        }
        return count;
    }

    private static @Nullable Order openOrder(UUID player, int templateId) {
        for (Order order : orders) {
            if (order.player().equals(player) && order.templateId() == templateId) return order;
        }
        return null;
    }

    public enum OrderOutcome { OK, LIMIT, DISABLED, UNAFFORDABLE }

    /**
     * Whether the Bauamt hands out orders for the template right now: only while the treasury could pay the copy (a
     * daily budget used up today does not count, the copy is claimed later anyway).
     */
    public static boolean orderable(@NotNull Blueprint template, @NotNull Funding funding) {
        int payout = payout(template, funding);
        return template.enabled() && !funding.broke() && payout > 0 && funding.free() - payout >= funding.reserve();
    }

    /** Worker: the player takes an order for one copy of the template. */
    public static @NotNull OrderOutcome takeOrder(@NotNull UUID player, @NotNull Blueprint template) {
        if (!template.enabled()) return OrderOutcome.DISABLED;
        if (!orderable(template, refreshFunding())) return OrderOutcome.UNAFFORDABLE;
        if (openOrders(player, template.id()) >= ORDERS_PER_TEMPLATE) return OrderOutcome.LIMIT;
        Long id = Database.queryOne("INSERT INTO bauamt_orders (player_uuid, template_id) VALUES (?, ?) RETURNING id", row -> row.getLong(1),
                player, template.id());
        if (id == null) throw new IllegalStateException("no id returned");
        orders.add(new Order(id, player, template.id(), Instant.now()));
        return OrderOutcome.OK;
    }

    /** The player's copies that wait for their grant. */
    public static @NotNull List<Build> pending(@NotNull UUID player) {
        List<Build> result = new ArrayList<>();
        for (Build build : builds) {
            if (build.state() == Build.State.PENDING && build.player().equals(player)) result.add(build);
        }
        return result;
    }

    public static @NotNull List<Build> damaged() {
        List<Build> result = new ArrayList<>();
        for (Build build : builds) {
            if (build.state() == Build.State.DAMAGED) result.add(build);
        }
        result.sort((a, b) -> {
            Instant one = a.damagedAt(), other = b.damagedAt();
            return one == null || other == null ? 0 : other.compareTo(one);
        });
        return result;
    }

    /** The latest paid copies, newest first. */
    public static @NotNull List<Build> recent(int limit) {
        List<Build> result = new ArrayList<>();
        for (Build build : builds) {
            if (build.paidAt() != null) result.add(build);
        }
        result.sort((a, b) -> b.paidAt().compareTo(a.paidAt()));
        return result.size() > limit ? result.subList(0, limit) : result;
    }


    /* noticing a finished copy (main thread, a tick after the piece was placed) */

    // placed pieces waiting for their check; drained on the next ticks within a time budget, so building with a
    // material of a big template never costs a tick more than the budget, however many blocks are placed at once
    private record Placement(Player player, World world, int x, int y, int z, Material material, @Nullable Entity cushion) {}
    private static final ArrayDeque<Placement> placements = new ArrayDeque<>();
    private static final int PLACEMENT_QUEUE_LIMIT = 2000;
    private static final long PLACEMENT_BUDGET_NANOS = 2_000_000L;
    private static boolean draining;

    /**
     * Main thread, from the place events: the piece is checked a tick later at the earliest (neighbours have settled).
     * cushion: the placed cushion entity, null for a block.
     */
    static void queuePlaced(@NotNull Player player, @NotNull World world, int x, int y, int z, @NotNull Material material, @Nullable Entity cushion) {
        if (!triggers.contains(material)) return;
        // a full queue drops the oldest piece: the newest is the one most likely to finish a copy
        if (placements.size() >= PLACEMENT_QUEUE_LIMIT) placements.poll();
        placements.add(new Placement(player, world, x, y, z, material, cushion));
        if (!draining) {
            draining = true;
            Tasks.sync(Bauamt::drainPlacements);
        }
    }

    private static void drainPlacements() {
        long deadline = System.nanoTime() + PLACEMENT_BUDGET_NANOS;
        try {
            Placement next;
            while ((next = placements.poll()) != null) {
                World world = next.world();
                // the piece must still be there; a block read needs its chunk loaded (it would load it otherwise)
                boolean present = next.cushion() != null ? next.cushion().isValid()
                        : world.isChunkLoaded(next.x() >> 4, next.z() >> 4) && world.getType(next.x(), next.y(), next.z()) == next.material();
                try {
                    if (next.player().isOnline() && present) placed(next.player(), world, next.x(), next.y(), next.z(), next.material());
                } catch (RuntimeException e) {
                    Main.getInstance().getSLF4JLogger().warn("Bauamt check of a placed piece failed", e);
                }
                if (System.nanoTime() > deadline) break;
            }
        } finally {
            // the next tick goes on with the rest; an empty queue lets the next placement start a new drain
            if (placements.isEmpty()) draining = false;
            else Tasks.syncLater(1L, Bauamt::drainPlacements);
        }
    }

    /** A block or cushion of this material was placed at the cell: is it the last piece of a copy? */
    static void placed(@NotNull Player player, @NotNull World world, int bx, int by, int bz, @NotNull Material material) {
        if (!triggers.contains(material)) return;
        for (Blueprint template : templates) {
            if (!template.enabled() || !template.materials().containsKey(material)) continue;
            for (Blueprint.Variant variant : template.variants()) {
                for (Blueprint.Cell cell : variant.cellsOf(material)) {
                    if (candidate(player, world, template, variant, bx - cell.dx(), by - cell.dy(), bz - cell.dz())) return;
                }
                for (Blueprint.Prop prop : variant.props()) {
                    if (prop.material() == material && candidate(player, world, template, variant, bx - prop.dx(), by - prop.dy(), bz - prop.dz())) return;
                }
            }
        }
    }

    // true once the copy at this origin was handled; false when nothing is there or another build is in the way
    private static boolean candidate(Player player, World world, Blueprint template, Blueprint.Variant variant, int ox, int oy, int oz) {
        if (oy < world.getMinHeight() || oy + variant.sizeY() > world.getMaxHeight()) return false;
        // one or two reads reject almost every wrong origin, before the chunks of the whole box are looked at
        if (!Blueprint.rarestMatch(world, ox, oy, oz, variant)) return false;
        if (!Blueprint.chunksLoaded(world, ox, oz, variant.sizeX(), variant.sizeZ())) return false;
        if (Blueprint.mismatches(world, ox, oy, oz, variant, 0) != 0) return false;
        Finished finished = finish(player, world.getName(), template, variant, ox, oy, oz);
        switch (finished.kind()) {
            case REGISTERED, PENDING -> hint(player, template, finished.build());
            case NO_ORDER -> orderHint(player, template);
            // a copy on top of another build is no new one; another offset may still be free
            case BLOCKED -> {
                return false;
            }
            default -> {}
        }
        return true;
    }

    /** What a complete copy means: newly reported, no order for it, repaired, already reported, known, in another build's way. */
    public enum Finish { REGISTERED, NO_ORDER, REPAIRED, PENDING, KNOWN, BLOCKED }

    /** build: the new or existing build (null for NO_ORDER). */
    public record Finished(@NotNull Finish kind, @Nullable Build build) {}

    /**
     * Main thread: the copy at this placement is complete. A new one is reported if the player holds an order, a damaged
     * one counts as repaired; nothing is said to the player.
     */
    static @NotNull Finished finish(@NotNull Player player, @NotNull String worldName, @NotNull Blueprint template, @NotNull Blueprint.Variant variant,
                                    int ox, int oy, int oz) {
        Build existing = overlapping(worldName, ox, oy, oz, variant.sizeX(), variant.sizeY(), variant.sizeZ());
        if (existing == null) {
            // money only for a copy the player took an order for
            Order order = openOrder(player.getUniqueId(), template.id());
            if (order == null) return new Finished(Finish.NO_ORDER, null);
            return new Finished(Finish.REGISTERED, register(player, template, variant, worldName, ox, oy, oz, order));
        }
        if (!existing.samePlace(template.id(), worldName, ox, oy, oz, variant.rotation(), variant.mirrored())) {
            return new Finished(Finish.BLOCKED, existing);
        }
        if (existing.state() == Build.State.DAMAGED) {
            repaired(existing, template);
            return new Finished(Finish.REPAIRED, existing);
        }
        if (existing.state() == Build.State.PENDING && existing.player().equals(player.getUniqueId())) {
            return new Finished(Finish.PENDING, existing);
        }
        return new Finished(Finish.KNOWN, existing);
    }

    private static @Nullable Build overlapping(String world, int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        for (Build build : builds) {
            if (build.state() != Build.State.GONE && build.overlaps(world, x, y, z, sizeX, sizeY, sizeZ)) return build;
        }
        return null;
    }

    private static Build register(Player player, Blueprint template, Blueprint.Variant variant, String world, int x, int y, int z, Order order) {
        Build build = new Build(0, template.id(), player.getUniqueId(), player.getName(), world, x, y, z,
                variant.sizeX(), variant.sizeY(), variant.sizeZ(), variant.rotation(), variant.mirrored(),
                Build.State.PENDING, 0, Instant.now(), null, null);
        builds.add(build);
        orders.remove(order);
        Tasks.async(() -> {
            try {
                Long id = Database.queryOne("""
                        INSERT INTO bauamt_builds (template_id, player_uuid, player_name, world, x, y, z, size_x, size_y, size_z, rotation, mirrored, state)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING') RETURNING id""", row -> row.getLong(1),
                        template.id(), player.getUniqueId(), player.getName(), world, x, y, z,
                        variant.sizeX(), variant.sizeY(), variant.sizeZ(), variant.rotation().name(), variant.mirrored());
                if (id == null) throw new IllegalStateException("no id returned");
                build.setId(id);
                Database.update("UPDATE bauamt_orders SET build_id = ? WHERE id = ?", id, order.id());
            } catch (RuntimeException e) {
                builds.remove(build);
                orders.add(order);
                Main.getInstance().getSLF4JLogger().error("Could not store the build of {}", player.getName(), e);
            }
        });
        return build;
    }

    static void hint(Player player, Blueprint template, Build build) {
        if (!build.shouldHint()) return;
        player.sendMessage(Main.getChatPrefix() + "§e§lDas sieht fertig aus! §fDein §e" + template.name() + " §fbei §e"
                + build.centerX() + " / " + build.centerZ() + " §fist gemeldet. Hol dir die Förderung beim §e§lBauamt§f ab.");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
    }

    // a finished copy without an order: told once in a while, so players learn where the orders are
    private static void orderHint(Player player, Blueprint template) {
        long now = System.currentTimeMillis();
        Long last = orderHints.get(player.getUniqueId());
        if (last != null && now - last < ORDER_HINT_COOLDOWN_MILLIS) return;
        orderHints.put(player.getUniqueId(), now);
        player.sendMessage(Main.getChatPrefix() + "§fDas sieht aus wie ein §e" + template.name() + "§f. Gefördert wird nur, wofür du vorher einen Auftrag beim §eBauamt §fangenommen hast.");
    }


    /* diagnosis for admins */

    /**
     * The best fitting placement of a template around a block: where, how turned, how many cells are wrong and the
     * first of them. cutShort: the search hit its time budget, a better placement may exist (name the template).
     */
    public record Diagnosis(@NotNull Blueprint template, @NotNull Blueprint.Variant variant, int ox, int oy, int oz, int bad,
                            @NotNull List<String> lines, @Nullable Build overlapping, boolean cutShort) {}

    // the search runs on the main thread: it stops after this long and reports the best placement so far
    private static final long DIAGNOSE_BUDGET_NANOS = 25_000_000L;

    /** Main thread: tries every placement in which the block is a piece of a template, null if it is part of none. */
    public static @Nullable Diagnosis diagnose(@NotNull World world, int bx, int by, int bz, @Nullable Blueprint only) {
        Material material = world.getType(bx, by, bz);
        long deadline = System.nanoTime() + DIAGNOSE_BUDGET_NANOS;
        boolean cutShort = false;
        Diagnosis best = null;
        search:
        for (Blueprint template : templates) {
            if (only != null && template.id() != only.id()) continue;
            for (Blueprint.Variant variant : template.variants()) {
                for (Blueprint.Cell cell : variant.cellsOf(material)) {
                    best = better(best, world, template, variant, bx - cell.dx(), by - cell.dy(), bz - cell.dz());
                    if (best != null && best.bad() == 0) break search;
                    if (System.nanoTime() > deadline) {
                        cutShort = true;
                        break search;
                    }
                }
                if (!material.isAir()) continue;
                // an empty block: maybe the admin looks at a cushion, try its cells with any colour
                for (Blueprint.Prop prop : variant.props()) {
                    best = better(best, world, template, variant, bx - prop.dx(), by - prop.dy(), bz - prop.dz());
                    if (best != null && best.bad() == 0) break search;
                    if (System.nanoTime() > deadline) {
                        cutShort = true;
                        break search;
                    }
                }
            }
        }
        if (best == null) return null;
        Blueprint.Variant variant = best.variant();
        return new Diagnosis(best.template(), variant, best.ox(), best.oy(), best.oz(), best.bad(),
                Blueprint.explain(world, best.ox(), best.oy(), best.oz(), variant, 8),
                overlapping(world.getName(), best.ox(), best.oy(), best.oz(), variant.sizeX(), variant.sizeY(), variant.sizeZ()), cutShort);
    }

    private static @Nullable Diagnosis better(@Nullable Diagnosis best, World world, Blueprint template, Blueprint.Variant variant, int ox, int oy, int oz) {
        if (oy < world.getMinHeight() || oy + variant.sizeY() > world.getMaxHeight()) return best;
        int stop = best == null ? Integer.MAX_VALUE : best.bad() - 1;
        if (stop < 0) return best;
        if (!Blueprint.chunksLoaded(world, ox, oz, variant.sizeX(), variant.sizeZ())) return best;
        int bad = Blueprint.mismatches(world, ox, oy, oz, variant, stop);
        if (best != null && bad >= best.bad()) return best;
        return new Diagnosis(template, variant, ox, oy, oz, bad, List.of(), null, false);
    }


    /* the grant, claimed at the villager */

    public enum Outcome { OK, BROKE, EMPTY, BUDGET, NOT_INTACT, UNLOADED, NOT_PENDING, ERROR }

    /** Main thread: loads the chunks of the copy, checks it again and pays; the outcome comes back on the main thread. */
    public static void claim(@NotNull Player player, @NotNull Build build, @NotNull Consumer<Outcome> onMain) {
        Blueprint template = template(build.templateId());
        Blueprint.Variant variant = template == null ? null : template.variant(build.rotation(), build.mirrored());
        World world = Bukkit.getWorld(build.world());
        if (build.state() != Build.State.PENDING || !build.player().equals(player.getUniqueId()) || build.id() <= 0
                || template == null || !template.enabled() || variant == null || world == null) {
            onMain.accept(Outcome.NOT_PENDING);
            return;
        }
        loadChunks(world, build, loaded -> {
            if (!loaded) {
                releaseChunks(world, build);
                onMain.accept(Outcome.UNLOADED);
                return;
            }
            verifyAndPay(player, build, template, variant, world, 0, onMain);
        });
    }

    // the cushions arrive a little after the blocks of a chunk: wait a few ticks for them, then check and pay
    private static void verifyAndPay(Player player, Build build, Blueprint template, Blueprint.Variant variant, World world, int attempt,
                                     Consumer<Outcome> onMain) {
        if (attempt < 20 && !Blueprint.chunksLoaded(world, build.x(), build.z(), build.sizeX(), build.sizeZ())) {
            Tasks.syncLater(5L, () -> verifyAndPay(player, build, template, variant, world, attempt + 1, onMain));
            return;
        }
        boolean intact = Blueprint.mismatches(world, build.x(), build.y(), build.z(), variant, 0) == 0;
        releaseChunks(world, build);
        if (!intact) {
            onMain.accept(Outcome.NOT_INTACT);
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(() -> {
            Outcome outcome;
            try {
                outcome = pay(build, uuid, template);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().error("Bauamt payout to {} failed", player.getName(), e);
                outcome = Outcome.ERROR;
            }
            Outcome result = outcome;
            MainThread.run(() -> onMain.accept(result));
        });
    }

    // worker: one grant at a time, so two claims cannot both pass the treasury check
    private static Outcome pay(Build build, UUID player, Blueprint template) {
        synchronized (PAY_LOCK) {
            Funding current = refreshFunding();
            if (current.broke()) return Outcome.BROKE;
            int payout = payout(template, current);
            // a claim passes while the budget is not used up (one grant may go over it, so big copies are not starved)
            if (payout > 0 && current.budgetLeft() <= 0) return Outcome.BUDGET;
            if (!affordable(payout, current)) return Outcome.EMPTY;
            Outcome outcome = Database.inTransaction(connection -> {
                // the same lock as Basil's interest and the bond repayments, and the free money read again under it:
                // two payouts never spend the same money
                Database.queryOne(connection, "SELECT pg_advisory_xact_lock(?)", row -> 1, Bank.INTEREST_LOCK);
                Bankruptcy.Level level = Bankruptcy.level(connection);
                Long owed = Database.queryOne(connection, "SELECT COALESCE(SUM(payout), 0) FROM state_bonds WHERE NOT repaid", row -> row.getLong(1));
                long free = level.free() - (owed == null ? 0 : owed);
                Long promised = Database.queryOne(connection, PROMISED_INTEREST, row -> row.getLong(1));
                long reserve = reserveOf(current.averageIncome(), level.brokeBelow(), promised == null ? 0 : promised);
                if (free - payout < reserve) return Outcome.EMPTY;
                Long granted = Database.queryOne(connection, GRANTED_DAY, row -> row.getLong(1));
                if (budgetLeftOf(current.averageIncome(), granted == null ? 0 : granted) <= 0) return Outcome.BUDGET;
                if (Database.update(connection, "UPDATE bauamt_builds SET state = 'INTACT', paid_at = now(), reward = ?, checked_at = now()"
                        + " WHERE id = ? AND state = 'PENDING'", payout, build.id()) == 0) return Outcome.NOT_PENDING;
                Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", payout, player);
                Treasury.withdraw(connection, Treasury.Source.BUILDING_GRANT, payout, player);
                return Outcome.OK;
            });
            if (outcome == Outcome.OK) {
                build.paid(payout);
                rebuildIndex();
                Main.getInstance().getSLF4JLogger().info("Bauamt grant {} ({} % of {}) to {} for build {}", payout, current.percent(),
                        template.reward(), player, build.id());
                Treasury.committed(-payout);
                refreshFunding();
            }
            return outcome;
        }
    }

    // the chunks of the copy, kept with a ticket until the check is done; Paper calls back on the main thread
    private static void loadChunks(World world, Build build, Consumer<Boolean> onMain) {
        int minCx = build.x() >> 4, maxCx = (build.x() + build.sizeX() - 1) >> 4;
        int minCz = build.z() >> 4, maxCz = (build.z() + build.sizeZ() - 1) >> 4;
        int[] open = {(maxCx - minCx + 1) * (maxCz - minCz + 1)};
        boolean[] failed = {false};
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                int chunkX = cx, chunkZ = cz;
                world.getChunkAtAsync(chunkX, chunkZ, false, chunk -> {
                    Runnable arrived = () -> {
                        if (chunk == null) failed[0] = true;
                        else world.addPluginChunkTicket(chunkX, chunkZ, Main.getInstance());
                        if (--open[0] == 0) onMain.accept(!failed[0]);
                    };
                    if (Bukkit.isPrimaryThread()) arrived.run();
                    else Tasks.sync(arrived);
                });
            }
        }
    }

    private static void releaseChunks(World world, Build build) {
        for (int cx = build.x() >> 4; cx <= (build.x() + build.sizeX() - 1) >> 4; cx++) {
            for (int cz = build.z() >> 4; cz <= (build.z() + build.sizeZ() - 1) >> 4; cz++) {
                world.removePluginChunkTicket(cx, cz, Main.getInstance());
            }
        }
    }


    /* state property: who breaks a piece of a paid build pays for it */

    // every build that belongs to the state (paid, or the admin's model), by world and chunk; runs on workers (payout,
    // admin) and on the main thread (written off): one at a time, so a stale map can never replace a newer one
    private static synchronized void rebuildIndex() {
        Map<String, Map<Long, List<Build>>> fresh = new HashMap<>();
        for (Build build : builds) {
            if (build.state() != Build.State.INTACT && build.state() != Build.State.DAMAGED) continue;
            Map<Long, List<Build>> chunks = fresh.computeIfAbsent(build.world(), world -> new HashMap<>());
            for (int cx = build.x() >> 4; cx <= (build.x() + build.sizeX() - 1) >> 4; cx++) {
                for (int cz = build.z() >> 4; cz <= (build.z() + build.sizeZ() - 1) >> 4; cz++) {
                    chunks.computeIfAbsent(chunkKey(cx, cz), key -> new ArrayList<>()).add(build);
                }
            }
        }
        index = fresh;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /** The state's build whose box holds the cell, null for almost every block of the world (one map lookup). */
    public static @Nullable Build buildAt(@NotNull String world, int x, int y, int z) {
        Map<Long, List<Build>> chunks = index.get(world);
        if (chunks == null) return null;
        List<Build> candidates = chunks.get(chunkKey(x >> 4, z >> 4));
        if (candidates == null) return null;
        for (Build build : candidates) {
            if (build.overlaps(world, x, y, z, 1, 1, 1)) return build;
        }
        return null;
    }

    /**
     * Main thread, from the block break event: charges the player if the block was a piece of a state build, or the
     * ground a bottom piece rests on and that piece fell with it (checked a tick later).
     */
    static void blockBroken(@NotNull Player player, @NotNull Block block) {
        Build build = buildAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (build != null) {
            Blueprint template = template(build.templateId());
            if (template != null && isPiece(build, block)) charge(player, build, template, 1);
            return;
        }
        Build carried = supportsPiece(block);
        if (carried == null) return;
        Block above = block.getRelative(BlockFace.UP);
        Tasks.syncLater(1L, () -> {
            Blueprint template = template(carried.templateId());
            if (template != null && player.isOnline() && !isPiece(carried, above)) charge(player, carried, template, 1);
        });
    }

    /** Main thread, a tick after a cushion was hit and vanished. */
    static void cushionBroken(@NotNull Player player, @NotNull Location at) {
        World world = at.getWorld();
        if (world == null) return;
        Build build = buildAt(world.getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (build == null) return;
        Blueprint template = template(build.templateId());
        Blueprint.Variant variant = template == null ? null : template.variant(build.rotation(), build.mirrored());
        if (variant == null || !variant.prop(at.getBlockX() - build.x(), at.getBlockY() - build.y(), at.getBlockZ() - build.z())) return;
        charge(player, build, template, 1);
    }

    /**
     * Main thread, from the explode events (the blocks are still there): pieces of state builds stay. A blown-up copy
     * could otherwise be rebuilt from its own drops and paid a second time, and most explosions have nobody to charge.
     */
    static void spareFromExplosion(@NotNull World world, @NotNull List<Block> blocks) {
        if (blocks.isEmpty() || !index.containsKey(world.getName())) return;
        blocks.removeIf(block -> isStatePiece(block) || supportsPiece(block) != null);
    }

    /**
     * The build whose bottom piece rests on this block (a door, rail, carpet, torch ... that would pop off without it),
     * null for almost every block. The ground under a copy is outside its box, yet the copy needs it.
     */
    private static @Nullable Build supportsPiece(@NotNull Block block) {
        Block above = block.getRelative(BlockFace.UP);
        Build build = buildAt(above.getWorld().getName(), above.getX(), above.getY(), above.getZ());
        if (build == null || above.getY() != build.y() || above.getType().isOccluding()) return null;
        return isPiece(build, above) ? build : null;
    }

    /** Main thread: a block placed or a bucket used here would replace a piece of a state build (grass, snow, water ...). */
    static boolean replacesStatePiece(@NotNull Block block, @NotNull BlockData before) {
        Build build = buildAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (build == null) return false;
        Blueprint template = template(build.templateId());
        Blueprint.Variant variant = template == null ? null : template.variant(build.rotation(), build.mirrored());
        if (variant == null) return false;
        BlockData expected = variant.data(block.getX() - build.x(), block.getY() - build.y(), block.getZ() - build.z());
        return expected != null && Blueprint.same(expected, before);
    }

    /** Main thread: the cell lies in the box of a paid build (a piece or a gap that has to stay empty). */
    static boolean inStateBox(@NotNull Block block) {
        return buildAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()) != null;
    }

    /** Main thread: the block is a piece of a paid build (state property); a single map lookup for any other block. */
    static boolean isStatePiece(@NotNull Block block) {
        Build build = buildAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        return build != null && isPiece(build, block);
    }

    /** Main thread: a cushion at this spot belongs to a paid build. */
    static boolean isStateProp(@NotNull Location at) {
        World world = at.getWorld();
        if (world == null) return false;
        Build build = buildAt(world.getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (build == null) return false;
        Blueprint template = template(build.templateId());
        Blueprint.Variant variant = template == null ? null : template.variant(build.rotation(), build.mirrored());
        return variant != null && variant.prop(at.getBlockX() - build.x(), at.getBlockY() - build.y(), at.getBlockZ() - build.z());
    }

    /**
     * Main thread, from the piston events: a piston must not move pieces of a state build, nor push into one, unless it
     * is a part of that build itself (a door in the template). direction: where the blocks move.
     */
    static boolean pistonTouchesBuild(@NotNull Block piston, @NotNull List<Block> moved, @NotNull BlockFace direction, boolean extending) {
        String world = piston.getWorld().getName();
        if (!index.containsKey(world)) return false;
        // a part of the build only if the template has this piston there; one placed into a gap is an outsider
        Build inBox = buildAt(world, piston.getX(), piston.getY(), piston.getZ());
        Build own = inBox != null && isPiece(inBox, piston) ? inBox : null;
        if (extending && foreign(world, piston.getRelative(direction), own)) return true;
        for (Block block : moved) {
            if (foreign(world, block, own) || foreign(world, block.getRelative(direction), own)) return true;
        }
        return false;
    }

    private static boolean foreign(String world, Block block, @Nullable Build own) {
        Build build = buildAt(world, block.getX(), block.getY(), block.getZ());
        return build != null && build != own;
    }

    // only the block the template wants there, not something that was placed into a gap
    private static boolean isPiece(Build build, Block block) {
        Blueprint template = template(build.templateId());
        Blueprint.Variant variant = template == null ? null : template.variant(build.rotation(), build.mirrored());
        if (variant == null) return false;
        BlockData expected = variant.data(block.getX() - build.x(), block.getY() - build.y(), block.getZ() - build.z());
        return expected != null && Blueprint.same(expected, block.getBlockData());
    }

    private static void charge(Player player, Build build, Blueprint template, int blocks) {
        if (PermissionsUtil.isPlayerAdmin(player)) return;
        UUID uuid = player.getUniqueId();
        Damage open = null;
        for (Damage damage : damages) {
            // damage after a served warrant gets a bill of its own (with its own due date and warrant)
            if (damage.player().equals(uuid) && damage.buildId() == build.id() && !damage.served()) {
                open = damage;
                break;
            }
        }
        if (open != null) {
            open.addBlocks(blocks);
            Damage damage = open;
            // a bill paid meanwhile is not raised afterwards (the payment covered the amount it summed)
            if (damage.id() > 0) Tasks.async(() -> Database.update("UPDATE bauamt_damage SET blocks = ?, amount = ? WHERE id = ? AND paid_at IS NULL",
                    damage.blocks(), damage.amount(), damage.id()));
            if (damage.shouldNotice(NOTICE_COOLDOWN_MILLIS)) {
                player.sendMessage(Main.getChatPrefix() + "§fSchaden am §e" + template.name() + "§f: jetzt §c" + Items.format(damage.amount())
                        + " Schilling §7(" + damage.blocks() + " Blöcke)§f.");
            }
            return;
        }
        Instant now = Instant.now();
        Damage created = new Damage(0, build.id(), uuid, player.getName(), blocks, DAMAGE_FLAT + DAMAGE_PER_BLOCK * blocks, now,
                now.plus(Duration.ofDays(DAMAGE_DUE_DAYS)), false, false, false);
        created.shouldNotice(NOTICE_COOLDOWN_MILLIS);
        damages.add(created);
        Tasks.async(() -> {
            try {
                Long id = Database.queryOne("INSERT INTO bauamt_damage (build_id, player_uuid, player_name, blocks, amount, due_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id", row -> row.getLong(1),
                        build.id(), uuid, player.getName(), created.blocks(), created.amount(), Timestamp.from(created.dueAt()));
                if (id == null) throw new IllegalStateException("no id returned");
                created.setId(id);
                // blocks broken while the row was being written
                Database.update("UPDATE bauamt_damage SET blocks = ?, amount = ? WHERE id = ?", created.blocks(), created.amount(), id);
            } catch (RuntimeException e) {
                damages.remove(created);
                Main.getInstance().getSLF4JLogger().error("Could not store the damage by {}", player.getName(), e);
            }
        });
        player.sendMessage(Main.getChatPrefix() + "§c§lStaatseigentum beschädigt! §fDer §e" + template.name() + " §fbei §e"
                + build.centerX() + " / " + build.centerZ() + " §fgehört dem Staat.");
        player.sendMessage(Main.getChatPrefix() + "§fSchaden: §c" + Items.format(created.amount()) + " Schilling §7(" + Items.format(DAMAGE_FLAT)
                + " Pauschale + " + DAMAGE_PER_BLOCK + " je Block)§f. Zahl binnen " + DAMAGE_DUE_DAYS + " Tagen beim §eBauamt§f, sonst wirst du gesucht.");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
    }

    /** The player's unpaid damage bills. */
    public static @NotNull List<Damage> damages(@NotNull UUID player) {
        List<Damage> result = new ArrayList<>();
        for (Damage damage : damages) {
            if (damage.player().equals(player)) result.add(damage);
        }
        return result;
    }

    public static int owed(@NotNull UUID player) {
        int total = 0;
        for (Damage damage : damages) {
            if (damage.player().equals(player)) total += damage.amount();
        }
        return total;
    }

    /** Main thread, after the join: a reminder of what is still owed. */
    public static void remind(@NotNull Player player) {
        List<Damage> open = damages(player.getUniqueId());
        if (open.isEmpty()) return;
        int total = 0;
        Instant due = null;
        for (Damage damage : open) {
            total += damage.amount();
            if (due == null || damage.dueAt().isBefore(due)) due = damage.dueAt();
        }
        long days = Duration.between(Instant.now(), due).toDays();
        player.sendMessage(Main.getChatPrefix() + "§fDu schuldest dem Staat §c" + Items.format(total) + " Schilling §fSchadensersatz. "
                + (days < 0 ? "§cÜberfällig." : days == 0 ? "§cHeute fällig" : "§7Fällig in " + days + (days == 1 ? " Tag" : " Tagen"))
                + "§7, zahlbar beim Bauamt.");
    }

    // warrantEnded: the paid bills were the last reason the player was wanted, the caller lifts the warrant
    public record Settlement(int paid, int missing, boolean warrantEnded) {}

    /** Worker: takes every open damage bill of the player (into the treasury), or tells how much is missing. */
    public static @NotNull Settlement payDamages(@NotNull UUID player) {
        synchronized (PAY_LOCK) {
            Set<Long> paidIds = new HashSet<>();
            Settlement result = Database.inTransaction(connection -> {
                // the rows are locked: a block broken meanwhile waits and then lands on a new bill
                List<long[]> rows = Database.query(connection,
                        "SELECT id, amount FROM bauamt_damage WHERE player_uuid = ? AND paid_at IS NULL FOR UPDATE",
                        row -> new long[]{row.getLong(1), row.getLong(2)}, player);
                long total = 0;
                for (long[] row : rows) total += row[1];
                if (total <= 0) return new Settlement(0, 0, false);
                Long money = Database.queryOne(connection, "SELECT money FROM players WHERE uuid = ? FOR UPDATE", row -> row.getLong(1), player);
                long have = money == null ? 0 : money;
                if (have < total) return new Settlement(0, (int) (total - have), false);
                Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ?", total, player);
                // exactly the rows that were summed
                for (long[] row : rows) Database.update(connection, "UPDATE bauamt_damage SET paid_at = now() WHERE id = ?", row[0]);
                Treasury.deposit(connection, Treasury.Source.DAMAGE_FINE, total, player);
                paidIds.clear();
                for (long[] row : rows) paidIds.add(row[0]);
                return new Settlement((int) total, 0, false);
            });
            if (result.paid() > 0) {
                // a paid bill that started the running warrant ends it, unless another unserved debt keeps it going
                boolean warrantBill = false;
                for (Damage damage : damages) {
                    if (damage.player().equals(player) && paidIds.contains(damage.id()) && damage.wanted() && !damage.served()
                            && !damage.shared()) warrantBill = true;
                }
                // only the bills that were paid; one still being written (no id yet) stays open
                damages.removeIf(damage -> damage.player().equals(player) && paidIds.contains(damage.id()));
                Main.getInstance().getSLF4JLogger().info("Bauamt damage paid {} by {}", result.paid(), player);
                Treasury.committed(result.paid());
                refreshFunding();
                if (warrantBill && wantedBill(player) == 0 && !Loans.defaultUnserved(player)) {
                    return new Settlement(result.paid(), 0, true);
                }
            }
            return result;
        }
    }

    // main thread: bills past their due date make the player wanted, once; a prisoner only after the release (the
    // bill stays due and is tried again at the next inspection)
    private static void checkDamages(Instant now) {
        Map<UUID, List<Damage>> due = new HashMap<>();
        for (Damage damage : damages) {
            if (damage.wanted() || damage.dueAt().isAfter(now)) continue;
            due.computeIfAbsent(damage.player(), uuid -> new ArrayList<>()).add(damage);
        }
        due.forEach((uuid, bills) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                // a prisoner or a player wanted already: the bill waits and starts its own warrant afterwards
                if (!JailHandler.markWantedUntilCaughtIfFree(player, DAMAGE_REASON)) return;
                markWanted(bills);
                player.sendMessage(Main.getChatPrefix() + "§cDu hast den Schaden am Staatseigentum nicht bezahlt, jetzt wirst du gesucht. §7Die Rechnung bleibt offen.");
                return;
            }
            // marked first so the next run does not start a second write; taken back if the player sits in jail
            bills.forEach(Damage::markWanted);
            String name = bills.getFirst().playerName();
            Tasks.async(() -> {
                if (!PlayerRepository.setWantedUntilCaughtIfFree(uuid)) {
                    bills.forEach(Damage::unmarkWanted);
                    return;
                }
                storeWanted(bills);
                JailHandler.broadcastWanted(name, DAMAGE_REASON);
            });
        });
    }

    private static void markWanted(List<Damage> bills) {
        bills.forEach(Damage::markWanted);
        Tasks.async(() -> storeWanted(bills));
    }

    // worker; a bill still being written (no id yet) is marked again after the next restart at the latest
    private static void storeWanted(List<Damage> bills) {
        for (Damage damage : bills) {
            if (damage.id() > 0) Database.update("UPDATE bauamt_damage SET wanted_at = now() WHERE id = ?", damage.id());
        }
    }

    /** What the overdue bills behind the running warrant (wanted, not served yet) still come to; caps the bounty. */
    public static int wantedBill(@NotNull UUID player) {
        int total = 0;
        for (Damage damage : damages) {
            if (damage.player().equals(player) && damage.wanted() && !damage.served()) total += damage.amount();
        }
        return total;
    }

    /**
     * Any thread (blocking): the warrant the overdue bills started was served, they stay owed but cap no later bounty
     * (see Loans.warrantServed). A bill still being written (no id yet) is only marked in memory.
     */
    public static void serveBills(@NotNull UUID player) {
        for (Damage damage : damages) {
            if (!damage.player().equals(player) || !damage.wanted() || damage.served()) continue;
            if (damage.id() > 0) Database.update("UPDATE bauamt_damage SET served_at = now() WHERE id = ?", damage.id());
            damage.markServed();
        }
    }

    /**
     * Any thread (blocking): another reason joined the running warrant (black market, an admin): the bills that started
     * it still cap the bounty, but paying them no longer ends it.
     */
    public static void warrantShared(@NotNull UUID player) {
        for (Damage damage : damages) {
            if (!damage.player().equals(player) || !damage.wanted() || damage.served() || damage.shared()) continue;
            if (damage.id() > 0) Database.update("UPDATE bauamt_damage SET warrant_shared = TRUE WHERE id = ?", damage.id());
            damage.markShared();
        }
    }


    /* the inspection */

    public static void startTask() {
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), Bauamt::inspect, 20L * 60, INSPECT_PERIOD_TICKS);
    }

    // main thread: a few builds per run, only those in loaded chunks (nothing is loaded for it); bills and orders
    static void inspect() {
        Instant now = Instant.now();
        List<Build> active = new ArrayList<>();
        for (Build build : builds) {
            switch (build.state()) {
                case PENDING -> {
                    if (build.createdAt().isBefore(now.minus(Duration.ofDays(PENDING_EXPIRES_DAYS)))) expire(build);
                }
                // written off only after a last look at it (check), never on its age alone
                case DAMAGED -> active.add(build);
                case INTACT -> active.add(build);
                case GONE -> builds.remove(build);
            }
        }
        if (!active.isEmpty()) {
            int checks = Math.min(INSPECT_PER_RUN, active.size());
            List<Build> due = new ArrayList<>(checks);
            for (int i = 0; i < checks; i++) due.add(active.get((cursor + i) % active.size()));
            cursor = (cursor + checks) % active.size();
            checkNext(due, 0);
        }
        for (Order order : orders) {
            if (order.takenAt().isBefore(now.minus(Duration.ofDays(ORDER_EXPIRES_DAYS)))) {
                orders.remove(order);
                Tasks.async(() -> Database.update("DELETE FROM bauamt_orders WHERE id = ? AND build_id IS NULL", order.id()));
            }
        }
        checkDamages(now);
        Tasks.async(Bauamt::refreshFunding);
    }

    // one build per tick that really gets read (those in unloaded chunks are skipped in the same tick), not all at once
    private static void checkNext(List<Build> due, int from) {
        int i = from;
        while (i < due.size()) {
            if (check(due.get(i++))) break;
        }
        if (i < due.size()) {
            int next = i;
            Tasks.syncLater(1L, () -> checkNext(due, next));
        }
    }

    // true if the build was read; counting stops at the threshold that decides the state
    private static boolean check(Build build) {
        if (build.state() != Build.State.INTACT && build.state() != Build.State.DAMAGED) return false;
        World world = Bukkit.getWorld(build.world());
        if (world == null || !Blueprint.chunksLoaded(world, build.x(), build.z(), build.sizeX(), build.sizeZ())) return false;
        Blueprint template = template(build.templateId());
        Blueprint.Variant variant = template == null ? null : template.variant(build.rotation(), build.mirrored());
        if (variant == null) return false;
        // only the pieces count: filling the gaps of a paid copy must not get it written off (and paid again)
        int total = variant.pieces();
        if (total == 0) return true;
        // counting stops where the decision cannot change any more (the cut-offs use the very comparison below)
        boolean intactNow = build.state() == Build.State.INTACT;
        int stopAfter = intactNow ? damageCutoff(total) : repairCutoff(total);
        int bad = Blueprint.mismatches(world, build.x(), build.y(), build.z(), variant, stopAfter, false);
        double intact = 1.0 - (double) bad / total;
        if (intactNow && intact < DAMAGED_BELOW) {
            damaged(build, template);
        } else if (!intactNow && intact >= REPAIRED_AT) {
            repaired(build, template);
        } else if (!intactNow) {
            Instant damagedAt = build.damagedAt();
            if (damagedAt != null && damagedAt.isBefore(Instant.now().minus(Duration.ofDays(GONE_AFTER_DAYS)))) gone(build);
        }
        return true;
    }

    // the most wrong cells an intact build may have and stay intact: one more makes `intact < DAMAGED_BELOW` true
    // (searched with that double comparison itself, a plain floor is off by one for totals like 10, 15, 20)
    static int damageCutoff(int total) {
        int k = Math.max(0, (int) (total * (1.0 - DAMAGED_BELOW)) - 1);
        while (!(1.0 - (double) (k + 1) / total < DAMAGED_BELOW)) k++;
        return k;
    }

    // the most wrong cells a damaged build may have and count as repaired (`intact >= REPAIRED_AT`)
    static int repairCutoff(int total) {
        int m = (int) (total * (1.0 - REPAIRED_AT)) + 1;
        while (m > 0 && !(1.0 - (double) m / total >= REPAIRED_AT)) m--;
        return m;
    }

    private static void damaged(Build build, Blueprint template) {
        build.damaged();
        Tasks.async(() -> Database.update("UPDATE bauamt_builds SET state = 'DAMAGED', damaged_at = now(), checked_at = now() WHERE id = ?", build.id()));
        Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§c§lVandalismus! §fDer §e" + template.name() + " §fvon §e" + build.playerName()
                + " §fbei §e" + build.centerX() + " / " + build.centerZ() + " §fwurde beschädigt. §7Das Bauamt ermittelt."));
    }

    private static void repaired(Build build, Blueprint template) {
        build.repaired();
        Tasks.async(() -> Database.update("UPDATE bauamt_builds SET state = 'INTACT', damaged_at = NULL, checked_at = now() WHERE id = ?", build.id()));
        Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§a§lInstand gesetzt! §fDer §e" + template.name() + " §fbei §e"
                + build.centerX() + " / " + build.centerZ() + " §fsteht wieder."));
    }

    // written off: the place is free for a new copy
    private static void gone(Build build) {
        build.gone();
        builds.remove(build);
        rebuildIndex();
        Tasks.async(() -> Database.update("UPDATE bauamt_builds SET state = 'GONE', checked_at = now() WHERE id = ?", build.id()));
    }

    // a report nobody claimed in two weeks
    private static void expire(Build build) {
        builds.remove(build);
        if (build.id() > 0) Tasks.async(() -> Database.update("DELETE FROM bauamt_builds WHERE id = ? AND state = 'PENDING'", build.id()));
    }


    /* admin (blocking, on a worker) */

    /** Stores a template; its model becomes a watched build of the admin so nobody can claim the original. */
    public static @NotNull Blueprint create(@NotNull String name, int reward, @NotNull World world, @NotNull Blueprint.Capture capture,
                                            @NotNull Player admin) {
        Integer id = Database.queryOne("""
                INSERT INTO bauamt_templates (name, reward, world, origin_x, origin_y, origin_z, size_x, size_y, size_z, blocks)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", row -> row.getInt(1),
                name, reward, world.getName(), capture.minX(), capture.minY(), capture.minZ(),
                capture.sizeX(), capture.sizeY(), capture.sizeZ(), Blueprint.serialize(capture.blocks(), capture.props()));
        if (id == null) throw new IllegalStateException("no id returned");
        Blueprint template = new Blueprint(id, name, reward, true, world.getName(), capture.minX(), capture.minY(), capture.minZ(),
                capture.sizeX(), capture.sizeY(), capture.sizeZ(), capture.blocks(), capture.props());
        Long buildId = Database.queryOne("""
                INSERT INTO bauamt_builds (template_id, player_uuid, player_name, world, x, y, z, size_x, size_y, size_z, rotation, mirrored, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NONE', FALSE, 'INTACT') RETURNING id""", row -> row.getLong(1),
                id, admin.getUniqueId(), admin.getName(), world.getName(), capture.minX(), capture.minY(), capture.minZ(),
                capture.sizeX(), capture.sizeY(), capture.sizeZ());
        builds.add(new Build(buildId == null ? 0 : buildId, id, admin.getUniqueId(), admin.getName(), world.getName(),
                capture.minX(), capture.minY(), capture.minZ(), capture.sizeX(), capture.sizeY(), capture.sizeZ(),
                StructureRotation.NONE, false, Build.State.INTACT, 0, Instant.now(), null, null));
        reloadTemplates();
        rebuildIndex();
        return template;
    }

    public static void setEnabled(@NotNull Blueprint template, boolean enabled) {
        Database.update("UPDATE bauamt_templates SET enabled = ? WHERE id = ?", enabled, template.id());
        reloadTemplates();
    }

    public static void setReward(@NotNull Blueprint template, int reward) {
        Database.update("UPDATE bauamt_templates SET reward = ? WHERE id = ?", reward, template.id());
        reloadTemplates();
    }

    /** Removes the template with every build, order and bill of it (its places are free again). */
    public static void delete(@NotNull Blueprint template) {
        Database.update("DELETE FROM bauamt_templates WHERE id = ?", template.id());
        Set<Long> gone = new HashSet<>();
        for (Build build : builds) {
            if (build.templateId() == template.id()) gone.add(build.id());
        }
        // the players whose running warrant came from these bills alone
        Set<UUID> warrants = new HashSet<>();
        for (Damage damage : damages) {
            if (gone.contains(damage.buildId()) && damage.wanted() && !damage.served() && !damage.shared()) warrants.add(damage.player());
        }
        builds.removeIf(build -> build.templateId() == template.id());
        orders.removeIf(order -> order.templateId() == template.id());
        damages.removeIf(damage -> gone.contains(damage.buildId()));
        reloadTemplates();
        rebuildIndex();
        for (UUID uuid : warrants) {
            if (wantedBill(uuid) > 0 || Loans.defaultUnserved(uuid)) continue;
            PlayerRepository.clearUntilCaughtWarrant(uuid);
            MainThread.run(() -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) JailHandler.clearWantedIfUntilCaught(player);
            });
        }
    }

}
