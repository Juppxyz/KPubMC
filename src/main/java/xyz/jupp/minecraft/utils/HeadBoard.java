package xyz.jupp.minecraft.utils;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A board of SPOTS places, each a head block with a sign under it, set up by admins while looking at the head or the
 * sign (the Gesucht board, the death board). The top players hang there with their head, their name and an optional
 * detail line under the name; a free place gets a skeleton skull and empty lines. Refreshed every REFRESH_SECONDS, only
 * in loaded chunks, and compared with the real blocks, so a replaced head or sign is drawn again.
 */
public final class HeadBoard {

    public static final int SPOTS = 3;
    private static final int REFRESH_SECONDS = 20;
    // how far under the head the sign may be, and how far an admin may look at the block
    private static final int SIGN_SEARCH = 3;
    private static final int TARGET_RANGE = 6;
    private static final String PREFIX = Main.getChatPrefix();

    /** A place: the head block and the sign with the line that gets the name (the detail goes in the line under it). */
    public record Spot(int number, String world, int x, int y, int z, int signX, int signY, int signZ, int signLine) {}

    /** A player on the board, best first; detail is the line under the name (only on a board with a detail line). */
    public record Entry(UUID uuid, String name, @Nullable String detail) {}

    private final String table;
    private final String title;
    private final String what;
    private final @Nullable String detail;
    private final Supplier<List<Entry>> top;
    private volatile Map<Integer, Spot> spots = Map.of();

    /**
     * @param table  the place table (schema of wanted_board)
     * @param title  the board's name in messages, e.g. "Gesucht-Tafel"
     * @param what   what hangs there, for the admin's overview, e.g. "die 3 höchsten Kopfgelder"
     * @param detail what the line under the name shows, e.g. "die Tode"; null: that line is the builder's
     * @param top    worker thread: the best players, at least SPOTS of them if there are as many
     */
    public HeadBoard(@NotNull String table, @NotNull String title, @NotNull String what, @Nullable String detail,
                     @NotNull Supplier<List<Entry>> top) {
        this.table = table;
        this.title = title;
        this.what = what;
        this.detail = detail;
        this.top = top;
    }


    /* reading (any thread) */

    public int load() {
        Map<Integer, Spot> loaded = new HashMap<>();
        Database.query("SELECT * FROM " + table, row -> {
            Spot spot = new Spot(row.getInt("spot"), row.getString("world"), row.getInt("x"), row.getInt("y"), row.getInt("z"),
                    row.getInt("sign_x"), row.getInt("sign_y"), row.getInt("sign_z"), row.getInt("sign_line"));
            loaded.put(spot.number(), spot);
            return null;
        });
        spots = Map.copyOf(loaded);
        return loaded.size();
    }

    public Map<Integer, Spot> spots() {
        return spots;
    }

    public void startTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), this::refresh, 20L * 5, 20L * REFRESH_SECONDS);
    }


    /* setting up: /<command> [<Platz> [entfernen]] (main thread; the database part on a worker) */

    /** args: what follows the command (or its subcommand); usage: how the admin typed it, e.g. "/bestrafung tafel". */
    public void command(@NotNull CommandSender sender, @NotNull String usage, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(PREFIX + "§f" + title + " §7(" + what + ")§8:");
            for (int number = 1; number <= SPOTS; number++) {
                Spot spot = spots.get(number);
                sender.sendMessage("§8» §e" + number + " " + (spot == null ? "§7nicht gesetzt"
                        : "§7" + spot.world() + " " + spot.x() + " " + spot.y() + " " + spot.z()));
            }
            sender.sendMessage(PREFIX + "§fSetzen: §e" + usage + " <Platz> §7(schau dabei auf den Kopf oder das Schild darunter)");
            return;
        }
        int number;
        try {
            number = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            number = 0;
        }
        if (number < 1 || number > SPOTS) {
            sender.sendMessage(PREFIX + "§cDer Platz ist eine Zahl von 1 bis " + SPOTS + ".");
            return;
        }
        int place = number;
        if (args.length >= 2 && (args[1].equalsIgnoreCase("entfernen") || args[1].equalsIgnoreCase("löschen"))) {
            Tasks.supplyAsync(() -> remove(place), removed -> sender.sendMessage(PREFIX + (removed
                    ? "§fPlatz §e" + place + " §fgehört nicht mehr zur " + title + ". §7(Kopf und Schild bleiben stehen.)"
                    : "§cPlatz " + place + " war nicht gesetzt.")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cDie Tafel setzt man im Spiel, mit Blick auf den Kopf.");
            return;
        }
        Block target = player.getTargetBlockExact(TARGET_RANGE);
        Spot spot = target == null ? null : find(place, target);
        if (spot == null) {
            sender.sendMessage(PREFIX + "§cSchau auf einen Kopf oder Schädel mit einem Schild darunter §7(bis " + SIGN_SEARCH + " Blöcke tiefer)§c.");
            return;
        }
        Tasks.async(() -> {
            save(spot);
            String lines = "Der Name kommt in Zeile " + (spot.signLine() + 1)
                    + (detailLine(spot.signLine()) ? ", " + detail + " in Zeile " + (spot.signLine() + 2) : "") + " des Schilds.";
            Tasks.sync(() -> sender.sendMessage(PREFIX + "§fPlatz §e" + place + " §fder " + title + " gesetzt. §7(" + lines + ")"));
        });
    }

    /** Tab completion for the arguments after the command (or its subcommand). */
    public static List<String> complete(@NotNull String[] args) {
        if (args.length == 1) return startingWith(IntStream.rangeClosed(1, SPOTS).mapToObj(String::valueOf).toList(), args[0]);
        if (args.length == 2) return startingWith(List.of("entfernen"), args[1]);
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String typed) {
        return options.stream().filter(option -> option.toLowerCase().startsWith(typed.toLowerCase())).toList();
    }

    /** The place for a head or its sign: the head and the sign at most SIGN_SEARCH blocks under it, null if one is missing. */
    private static @Nullable Spot find(int number, @NotNull Block target) {
        Block head = target;
        Block sign = null;
        if (target.getState() instanceof Sign) {
            sign = target;
            head = null;
            for (int up = 1; up <= SIGN_SEARCH && head == null; up++) {
                Block above = target.getRelative(0, up, 0);
                if (above.getState() instanceof Skull) head = above;
            }
        } else if (target.getState() instanceof Skull) {
            for (int down = 1; down <= SIGN_SEARCH && sign == null; down++) {
                Block below = target.getRelative(0, -down, 0);
                if (below.getState() instanceof Sign) sign = below;
            }
        }
        if (head == null || sign == null) return null;
        // the name goes where the builder wrote something (e.g. "NAME"), else into the second line
        int line = 1;
        SignSide front = ((Sign) sign.getState()).getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            if (!Text.legacy(front.line(i)).isBlank()) {
                line = i;
                break;
            }
        }
        return new Spot(number, head.getWorld().getName(), head.getX(), head.getY(), head.getZ(), sign.getX(), sign.getY(), sign.getZ(), line);
    }

    // worker: stores the place and draws it right away
    private void save(@NotNull Spot spot) {
        Database.update("INSERT INTO " + table + """
                 (spot, world, x, y, z, sign_x, sign_y, sign_z, sign_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (spot) DO UPDATE SET world = EXCLUDED.world, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z,
                    sign_x = EXCLUDED.sign_x, sign_y = EXCLUDED.sign_y, sign_z = EXCLUDED.sign_z, sign_line = EXCLUDED.sign_line""",
                spot.number(), spot.world(), spot.x(), spot.y(), spot.z(), spot.signX(), spot.signY(), spot.signZ(), spot.signLine());
        Main.getInstance().getSLF4JLogger().info("{} spot {} at {} {} {} {}", table, spot.number(), spot.world(), spot.x(), spot.y(), spot.z());
        load();
        refresh();
    }

    // worker: forgets the place, its blocks stay as they are
    private boolean remove(int number) {
        boolean removed = Database.update("DELETE FROM " + table + " WHERE spot = ?", number) > 0;
        if (removed) load();
        return removed;
    }


    /* refresh */

    /** Worker: the top players, then the blocks on the main thread. */
    public void refresh() {
        if (spots.isEmpty()) return;
        List<Entry> entries;
        try {
            entries = top.get();
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().warn("{} could not be loaded: {}", title, e.toString());
            return;
        }
        if (Main.getInstance().isEnabled()) Tasks.sync(() -> draw(entries));
    }

    private void draw(List<Entry> entries) {
        for (Spot spot : spots.values()) {
            Entry entry = spot.number() <= entries.size() ? entries.get(spot.number() - 1) : null;
            World world = Bukkit.getWorld(spot.world());
            // never loads a chunk: an unloaded place is drawn once it is loaded and the next refresh comes
            if (world == null || !world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4) || !world.isChunkLoaded(spot.signX() >> 4, spot.signZ() >> 4)) {
                continue;
            }
            Block head = world.getBlockAt(spot.x(), spot.y(), spot.z());
            if (!showsHead(head, entry)) drawHead(head, entry);
            Block sign = world.getBlockAt(spot.signX(), spot.signY(), spot.signZ());
            if (!showsName(sign, spot.signLine(), entry)) drawName(sign, spot.signLine(), entry);
        }
    }

    // a skull block that is the right one already (a block that is no skull is left alone)
    private static boolean showsHead(Block block, @Nullable Entry entry) {
        if (!(block.getState() instanceof Skull skull)) return true;
        Material type = block.getType();
        if (entry == null) return type == Material.SKELETON_SKULL || type == Material.SKELETON_WALL_SKULL;
        if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) return false;
        ResolvableProfile profile = skull.getProfile();
        return profile != null && entry.uuid().equals(profile.uuid());
    }

    private boolean showsName(Block block, int line, @Nullable Entry entry) {
        if (!(block.getState() instanceof Sign sign)) return true;
        SignSide front = sign.getSide(Side.FRONT);
        if (!Text.legacy(front.line(line)).equals(entry == null ? "" : entry.name())) return false;
        return !detailLine(line) || Text.legacy(front.line(line + 1)).equals(detailText(entry));
    }

    // the line under the name, on a board that has one and if the name is not in the last line
    private boolean detailLine(int line) {
        return detail != null && line < 3;
    }

    private static String detailText(@Nullable Entry entry) {
        return entry == null || entry.detail() == null ? "" : entry.detail();
    }

    private static void drawHead(Block block, @Nullable Entry entry) {
        if (!(block.getState() instanceof Skull)) return;
        skullType(block, entry != null);
        if (entry == null) return;
        Skull skull = (Skull) block.getState();
        Player online = Bukkit.getPlayer(entry.uuid());
        // online the skin comes along; otherwise only the uuid, which the client resolves itself (with a name as well the
        // profile would count as complete and show the default skin)
        skull.setProfile(online != null ? ResolvableProfile.resolvableProfile(online.getPlayerProfile())
                : ResolvableProfile.resolvableProfile().uuid(entry.uuid()).build());
        skull.update(true, false);
    }

    // player head for a player, skeleton skull for a free place; on the wall or standing as before
    private static void skullType(Block block, boolean player) {
        BlockData data = block.getBlockData();
        boolean wall = data instanceof Directional;
        Material target = player ? (wall ? Material.PLAYER_WALL_HEAD : Material.PLAYER_HEAD)
                : (wall ? Material.SKELETON_WALL_SKULL : Material.SKELETON_SKULL);
        if (block.getType() == target) return;
        BlockData next = target.createBlockData();
        if (data instanceof Directional from && next instanceof Directional to) to.setFacing(from.getFacing());
        if (data instanceof Rotatable from && next instanceof Rotatable to) to.setRotation(from.getRotation());
        block.setBlockData(next, false);
    }

    // plain text: the sign's own dye colour stays
    private void drawName(Block block, int line, @Nullable Entry entry) {
        if (!(block.getState() instanceof Sign sign)) return;
        SignSide front = sign.getSide(Side.FRONT);
        front.line(line, entry == null ? Component.empty() : Component.text(entry.name()));
        if (detailLine(line)) front.line(line + 1, Component.text(detailText(entry)));
        // nobody edits the board by hand
        sign.setWaxed(true);
        sign.update(true, false);
    }

}
