package xyz.jupp.minecraft.building;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.economy.Bankruptcy;
import xyz.jupp.minecraft.economy.Bonds;
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
    /** Damage to state property: a flat fee per incident plus a sum per block, due within days, otherwise wanted. */
    public static final int DAMAGE_FLAT = 1000;
    public static final int DAMAGE_PER_BLOCK = 100;
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
    private static volatile Funding funding = new Funding(0, 0, 1.0, false, 0);
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
                row.getTimestamp("wanted_at") != null)));
        rebuildIndex();
        refreshFunding();
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
     * now, the reserve it keeps, and what is really free (not owed to savers and bond holders).
     */
    public record Funding(long balance, long averageIncome, double share, boolean broke, long free) {
        public boolean reduced() {
            return share < 1.0;
        }

        public int percent() {
            return (int) Math.round(share * 100);
        }

        /** What stays in the treasury whatever happens. */
        public long reserve() {
            return Math.round(averageIncome * RESERVE_SHARE);
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
        long average = income / INCOME_DAYS;
        long free = Bankruptcy.level().free() - Bonds.owed();
        Funding current = new Funding(balance, average, share(balance, average), Bankruptcy.isBroke(), free);
        funding = current;
        return current;
    }

    /** The full reward while the treasury holds its average daily income, below that the same share less. */
    static double share(long balance, long averageIncome) {
        if (averageIncome <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, (double) balance / averageIncome));
    }

    /** What a copy of the template pays right now (0 while the state is broke). */
    public static int payout(@NotNull Blueprint template) {
        return payout(template, funding);
    }

    static int payout(@NotNull Blueprint template, @NotNull Funding funding) {
        return funding.broke() ? 0 : (int) Math.round(template.reward() * funding.share());
    }

    /** Whether the treasury can pay this right now: it keeps its reserve and never spends what it owes. */
    public static boolean affordable(int payout, @NotNull Funding funding) {
        return !funding.broke() && payout > 0 && payout <= funding.free() && funding.balance() - payout >= funding.reserve();
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

    public enum OrderOutcome { OK, LIMIT, DISABLED }

    /** Worker: the player takes an order for one copy of the template. */
    public static @NotNull OrderOutcome takeOrder(@NotNull UUID player, @NotNull Blueprint template) {
        if (!template.enabled()) return OrderOutcome.DISABLED;
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

    /** A block or cushion of this material was placed at the cell: is it the last piece of a copy? */
    static void placed(@NotNull Player player, @NotNull World world, int bx, int by, int bz, @NotNull Material material) {
        if (!triggers.contains(material)) return;
        for (Blueprint template : templates) {
            if (!template.enabled()) continue;
            for (Blueprint.Variant variant : template.variants()) {
                for (Blueprint.Cell cell : variant.blocks()) {
                    if (cell.material() == material && candidate(player, world, template, variant, bx - cell.dx(), by - cell.dy(), bz - cell.dz())) return;
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
        if (!Blueprint.chunksLoaded(world, ox, oz, variant.sizeX(), variant.sizeZ())) return false;
        if (Blueprint.mismatches(world, ox, oy, oz, variant, 0) != 0) return false;
        String worldName = world.getName();
        Build existing = overlapping(worldName, ox, oy, oz, variant.sizeX(), variant.sizeY(), variant.sizeZ());
        if (existing == null) {
            // money only for a copy the player took an order for
            Order order = openOrder(player.getUniqueId(), template.id());
            if (order == null) orderHint(player, template);
            else register(player, template, variant, worldName, ox, oy, oz, order);
            return true;
        }
        if (existing.samePlace(template.id(), worldName, ox, oy, oz, variant.rotation(), variant.mirrored())) {
            if (existing.state() == Build.State.DAMAGED) {
                repaired(existing, template);
            } else if (existing.state() == Build.State.PENDING && existing.player().equals(player.getUniqueId())) {
                hint(player, template, existing);
            }
            return true;
        }
        // a copy on top of another build is no new one; another offset may still be free
        return false;
    }

    private static @Nullable Build overlapping(String world, int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        for (Build build : builds) {
            if (build.state() != Build.State.GONE && build.overlaps(world, x, y, z, sizeX, sizeY, sizeZ)) return build;
        }
        return null;
    }

    private static void register(Player player, Blueprint template, Blueprint.Variant variant, String world, int x, int y, int z, Order order) {
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
        hint(player, template, build);
    }

    private static void hint(Player player, Blueprint template, Build build) {
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


    /* the grant, claimed at the villager */

    public enum Outcome { OK, BROKE, EMPTY, NOT_INTACT, UNLOADED, NOT_PENDING, ERROR }

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
            if (!affordable(payout, current)) return Outcome.EMPTY;
            Outcome outcome = Database.inTransaction(connection -> {
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

    // every build that belongs to the state (paid, or the admin's model), by world and chunk
    private static void rebuildIndex() {
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

    /** Main thread, from the block break event: charges the player if the block was a piece of a state build. */
    static void blockBroken(@NotNull Player player, @NotNull Block block) {
        Build build = buildAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (build == null) return;
        Blueprint template = template(build.templateId());
        Blueprint.Variant variant = template == null ? null : template.variant(build.rotation(), build.mirrored());
        if (variant == null) return;
        // only the block the template wants there, not something that was placed into a gap
        var expected = variant.data(block.getX() - build.x(), block.getY() - build.y(), block.getZ() - build.z());
        if (expected == null || !Blueprint.same(expected, block.getBlockData())) return;
        charge(player, build, template);
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
        charge(player, build, template);
    }

    private static void charge(Player player, Build build, Blueprint template) {
        if (PermissionsUtil.isPlayerAdmin(player)) return;
        UUID uuid = player.getUniqueId();
        Damage open = null;
        for (Damage damage : damages) {
            if (damage.player().equals(uuid) && damage.buildId() == build.id()) {
                open = damage;
                break;
            }
        }
        if (open != null) {
            open.addBlock();
            Damage damage = open;
            if (damage.id() > 0) Tasks.async(() -> Database.update("UPDATE bauamt_damage SET blocks = ?, amount = ? WHERE id = ?",
                    damage.blocks(), damage.amount(), damage.id()));
            if (damage.shouldNotice(NOTICE_COOLDOWN_MILLIS)) {
                player.sendMessage(Main.getChatPrefix() + "§fSchaden am §e" + template.name() + "§f: jetzt §c" + Items.format(damage.amount())
                        + " Schilling §7(" + damage.blocks() + " Blöcke)§f.");
            }
            return;
        }
        Instant now = Instant.now();
        Damage created = new Damage(0, build.id(), uuid, player.getName(), 1, DAMAGE_FLAT + DAMAGE_PER_BLOCK, now,
                now.plus(Duration.ofDays(DAMAGE_DUE_DAYS)), false);
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

    public record Settlement(int paid, int missing) {}

    /** Worker: takes every open damage bill of the player (into the treasury), or tells how much is missing. */
    public static @NotNull Settlement payDamages(@NotNull UUID player) {
        synchronized (PAY_LOCK) {
            Settlement result = Database.inTransaction(connection -> {
                List<Long> amounts = Database.query(connection, "SELECT amount FROM bauamt_damage WHERE player_uuid = ? AND paid_at IS NULL",
                        row -> row.getLong(1), player);
                long total = amounts.stream().mapToLong(Long::longValue).sum();
                if (total <= 0) return new Settlement(0, 0);
                Long money = Database.queryOne(connection, "SELECT money FROM players WHERE uuid = ? FOR UPDATE", row -> row.getLong(1), player);
                long have = money == null ? 0 : money;
                if (have < total) return new Settlement(0, (int) (total - have));
                Database.update(connection, "UPDATE players SET money = money - ? WHERE uuid = ?", total, player);
                Database.update(connection, "UPDATE bauamt_damage SET paid_at = now() WHERE player_uuid = ? AND paid_at IS NULL", player);
                Treasury.deposit(connection, Treasury.Source.DAMAGE_FINE, total, player);
                return new Settlement((int) total, 0);
            });
            if (result.paid() > 0) {
                damages.removeIf(damage -> damage.player().equals(player));
                Main.getInstance().getSLF4JLogger().info("Bauamt damage paid {} by {}", result.paid(), player);
                Treasury.committed(result.paid());
                refreshFunding();
            }
            return result;
        }
    }

    // main thread: bills past their due date make the player wanted, once
    private static void checkDamages(Instant now) {
        Set<UUID> handled = new HashSet<>();
        for (Damage damage : damages) {
            if (damage.wanted() || damage.dueAt().isAfter(now)) continue;
            damage.markWanted();
            if (damage.id() > 0) Tasks.async(() -> Database.update("UPDATE bauamt_damage SET wanted_at = now() WHERE id = ?", damage.id()));
            if (!handled.add(damage.player())) continue;
            Player player = Bukkit.getPlayer(damage.player());
            if (player != null) {
                JailHandler.markWantedUntilCaught(player, DAMAGE_REASON);
                player.sendMessage(Main.getChatPrefix() + "§cDu hast den Schaden am Staatseigentum nicht bezahlt, jetzt wirst du gesucht. §7Die Rechnung bleibt offen.");
            } else {
                UUID uuid = damage.player();
                Tasks.async(() -> PlayerRepository.setWantedUntil(uuid, 0));
                JailHandler.broadcastWanted(damage.playerName(), DAMAGE_REASON);
            }
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
                case DAMAGED -> {
                    Instant damagedAt = build.damagedAt();
                    if (damagedAt != null && damagedAt.isBefore(now.minus(Duration.ofDays(GONE_AFTER_DAYS)))) gone(build);
                    else active.add(build);
                }
                case INTACT -> active.add(build);
                case GONE -> builds.remove(build);
            }
        }
        if (!active.isEmpty()) {
            int checks = Math.min(INSPECT_PER_RUN, active.size());
            for (int i = 0; i < checks; i++) check(active.get((cursor + i) % active.size()));
            cursor = (cursor + checks) % active.size();
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

    private static void check(Build build) {
        World world = Bukkit.getWorld(build.world());
        if (world == null || !Blueprint.chunksLoaded(world, build.x(), build.z(), build.sizeX(), build.sizeZ())) return;
        Blueprint template = template(build.templateId());
        Blueprint.Variant variant = template == null ? null : template.variant(build.rotation(), build.mirrored());
        if (variant == null) return;
        int total = variant.cells();
        int bad = Blueprint.mismatches(world, build.x(), build.y(), build.z(), variant, total);
        double intact = 1.0 - (double) bad / total;
        if (build.state() == Build.State.INTACT && intact < DAMAGED_BELOW) damaged(build, template);
        else if (build.state() == Build.State.DAMAGED && intact >= REPAIRED_AT) repaired(build, template);
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
        builds.removeIf(build -> build.templateId() == template.id());
        orders.removeIf(order -> order.templateId() == template.id());
        damages.removeIf(damage -> gone.contains(damage.buildId()));
        reloadTemplates();
        rebuildIndex();
    }

}
