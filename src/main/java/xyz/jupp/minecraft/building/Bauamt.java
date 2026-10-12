package xyz.jupp.minecraft.building;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.economy.Bankruptcy;
import xyz.jupp.minecraft.economy.Bonds;
import xyz.jupp.minecraft.economy.Market;
import xyz.jupp.minecraft.economy.Treasury;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The Bauamt: the state pays players for copies of its building templates. A copy is noticed when its last block is
 * placed (the player gets a hint), the player then claims the grant at the Bauamt villager, where the copy is checked
 * again and paid out of the treasury. The daily pot is a share of the free treasury above the recovery line of
 * {@link Bankruptcy}, so the grants can never make the state broke. Paid copies are inspected now and then, only in
 * loaded chunks; a broken one is announced, nothing records who broke it.
 */
public final class Bauamt {

    private Bauamt() {}

    /** Share of the free treasury above the recovery line that may be granted per day. */
    static final double DAILY_SHARE = 0.10;
    public static final int CLAIMS_PER_DAY = 3;
    // intact share below which a copy counts as damaged, and from which a damaged one counts as repaired
    static final double DAMAGED_BELOW = 0.80;
    static final double REPAIRED_AT = 0.98;
    static final int GONE_AFTER_DAYS = 7;
    static final int PENDING_EXPIRES_DAYS = 14;
    static final int INSPECT_PER_RUN = 20;
    static final long INSPECT_PERIOD_TICKS = 20L * 60 * 5;

    private static volatile List<Blueprint> templates = List.of();
    private static volatile Set<Material> triggers = Set.of();
    private static final List<Build> builds = new CopyOnWriteArrayList<>();
    private static volatile long pot;
    private static final Object PAY_LOCK = new Object();
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
        refreshPot();
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


    /* the pot */

    /** What the state can still grant today (cached; refreshed with every inspection, payout and menu). */
    public static long pot() {
        return pot;
    }

    /** Blocking: recomputes the pot. */
    public static long refreshPot() {
        Bankruptcy.Level level = Bankruptcy.level();
        long free = level.free() - Bonds.owed();
        long value = remaining(free, level.recoveredAt(), paidToday(), Bankruptcy.isBroke());
        pot = value;
        return value;
    }

    /** The share of what was free above the reserve at the start of the day, minus what was granted since. */
    static long remaining(long free, long reserve, long paidToday, boolean broke) {
        if (broke) return 0;
        long budget = (long) Math.floor((free + paidToday - reserve) * DAILY_SHARE);
        return Math.max(0, budget - paidToday);
    }

    static @NotNull Instant dayStart() {
        return LocalDate.now(Market.ZONE).atStartOfDay(Market.ZONE).toInstant();
    }

    private static long paidToday() {
        Long paid = Database.queryOne("SELECT COALESCE(-SUM(amount), 0) FROM treasury_ledger WHERE source = ? AND created_at >= ?",
                row -> row.getLong(1), Treasury.Source.BUILDING_GRANT.name(), Timestamp.from(dayStart()));
        return paid == null ? 0 : paid;
    }

    /** How many grants the player got today. */
    public static int claimsToday(@NotNull UUID player) {
        Instant since = dayStart();
        int count = 0;
        for (Build build : builds) {
            Instant paidAt = build.paidAt();
            if (build.player().equals(player) && paidAt != null && !paidAt.isBefore(since)) count++;
        }
        return count;
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


    /* noticing a finished copy (main thread, a tick after the block was placed) */

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
            register(player, template, variant, worldName, ox, oy, oz);
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

    private static void register(Player player, Blueprint template, Blueprint.Variant variant, String world, int x, int y, int z) {
        Build build = new Build(0, template.id(), player.getUniqueId(), player.getName(), world, x, y, z,
                variant.sizeX(), variant.sizeY(), variant.sizeZ(), variant.rotation(), variant.mirrored(),
                Build.State.PENDING, 0, Instant.now(), null, null);
        builds.add(build);
        Tasks.async(() -> {
            try {
                Long id = Database.queryOne("""
                        INSERT INTO bauamt_builds (template_id, player_uuid, player_name, world, x, y, z, size_x, size_y, size_z, rotation, mirrored, state)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING') RETURNING id""", row -> row.getLong(1),
                        template.id(), player.getUniqueId(), player.getName(), world, x, y, z,
                        variant.sizeX(), variant.sizeY(), variant.sizeZ(), variant.rotation().name(), variant.mirrored());
                if (id == null) throw new IllegalStateException("no id returned");
                build.setId(id);
            } catch (RuntimeException e) {
                builds.remove(build);
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


    /* the grant, claimed at the villager */

    public enum Outcome { OK, POT_EMPTY, DAILY_LIMIT, NOT_INTACT, UNLOADED, NOT_PENDING, ERROR }

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
        int reward = template.reward();
        UUID uuid = player.getUniqueId();
        Tasks.async(() -> {
            Outcome outcome;
            try {
                outcome = pay(build, uuid, reward);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().error("Bauamt payout to {} failed", player.getName(), e);
                outcome = Outcome.ERROR;
            }
            Outcome result = outcome;
            MainThread.run(() -> onMain.accept(result));
        });
    }

    // worker: one grant at a time, so two claims cannot both pass the pot check
    private static Outcome pay(Build build, UUID player, int reward) {
        synchronized (PAY_LOCK) {
            if (reward > refreshPot()) return Outcome.POT_EMPTY;
            Timestamp since = Timestamp.from(dayStart());
            Outcome outcome = Database.inTransaction(connection -> {
                Integer claims = Database.queryOne(connection, "SELECT COUNT(*) FROM bauamt_builds WHERE player_uuid = ? AND paid_at >= ?",
                        row -> row.getInt(1), player, since);
                if (claims != null && claims >= CLAIMS_PER_DAY) return Outcome.DAILY_LIMIT;
                if (Database.update(connection, "UPDATE bauamt_builds SET state = 'INTACT', paid_at = now(), reward = ?, checked_at = now()"
                        + " WHERE id = ? AND state = 'PENDING'", reward, build.id()) == 0) return Outcome.NOT_PENDING;
                Database.update(connection, "UPDATE players SET money = money + ? WHERE uuid = ?", reward, player);
                Treasury.withdraw(connection, Treasury.Source.BUILDING_GRANT, reward, player);
                return Outcome.OK;
            });
            if (outcome == Outcome.OK) {
                build.paid(reward);
                Main.getInstance().getSLF4JLogger().info("Bauamt grant {} to {} for build {}", reward, player, build.id());
                refreshPot();
                Treasury.committed(-reward);
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


    /* the inspection */

    public static void startTask() {
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), Bauamt::inspect, 20L * 60, INSPECT_PERIOD_TICKS);
    }

    // main thread: a few builds per run, only those in loaded chunks (nothing is loaded for it)
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
        Tasks.async(Bauamt::refreshPot);
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

    /** Removes the template with every build of it (its places are free again). */
    public static void delete(@NotNull Blueprint template) {
        Database.update("DELETE FROM bauamt_templates WHERE id = ?", template.id());
        builds.removeIf(build -> build.templateId() == template.id());
        reloadTemplates();
    }

    /** "12 × 3 × 5" */
    public static @NotNull String size(@NotNull Blueprint template) {
        return String.format(Locale.ROOT, "%d × %d × %d", template.sizeX(), template.sizeY(), template.sizeZ());
    }

}
