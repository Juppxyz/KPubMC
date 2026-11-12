package xyz.jupp.minecraft.jail;

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
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.economy.Loans;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The "Gesucht" board: up to SPOTS places, each a head block with a sign under it, set by admins with
 * /bestrafung tafel. The most wanted players (highest bounty first) hang there with their head and their name; a free
 * place gets a skeleton skull and an empty name line. Refreshed every REFRESH_SECONDS, only in loaded chunks.
 */
public final class WantedBoard {

    private WantedBoard() {}

    public static final int SPOTS = 3;
    private static final int REFRESH_SECONDS = 20;
    // how far under the head the sign may be, and how far an admin may look at the block
    private static final int SIGN_SEARCH = 3;
    public static final int TARGET_RANGE = 6;

    /** A place: the head block and the sign with the line that gets the name. */
    public record Spot(int number, String world, int x, int y, int z, int signX, int signY, int signZ, int signLine) {}

    private record Wanted(UUID uuid, String name, int bounty, long wantedEnd) {}

    private static volatile Map<Integer, Spot> spots = Map.of();


    /* reading (any thread) */

    public static int load() {
        Map<Integer, Spot> loaded = new HashMap<>();
        Database.query("SELECT * FROM wanted_board", row -> {
            Spot spot = new Spot(row.getInt("spot"), row.getString("world"), row.getInt("x"), row.getInt("y"), row.getInt("z"),
                    row.getInt("sign_x"), row.getInt("sign_y"), row.getInt("sign_z"), row.getInt("sign_line"));
            loaded.put(spot.number(), spot);
            return null;
        });
        spots = Map.copyOf(loaded);
        return loaded.size();
    }

    public static Map<Integer, Spot> spots() {
        return spots;
    }

    public static void startTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getInstance(), WantedBoard::refresh, 20L * 5, 20L * REFRESH_SECONDS);
    }


    /* setting up (main thread: blocks; the database part on a worker) */

    /** The board place for a head or its sign: the head and the sign at most SIGN_SEARCH blocks under it, null if one is missing. */
    public static @Nullable Spot find(int number, @NotNull Block target) {
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

    /** Worker: stores the place and redraws it with the next refresh. */
    public static void save(@NotNull Spot spot) {
        Database.update("""
                INSERT INTO wanted_board (spot, world, x, y, z, sign_x, sign_y, sign_z, sign_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (spot) DO UPDATE SET world = EXCLUDED.world, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z,
                    sign_x = EXCLUDED.sign_x, sign_y = EXCLUDED.sign_y, sign_z = EXCLUDED.sign_z, sign_line = EXCLUDED.sign_line""",
                spot.number(), spot.world(), spot.x(), spot.y(), spot.z(), spot.signX(), spot.signY(), spot.signZ(), spot.signLine());
        Main.getInstance().getSLF4JLogger().info("wanted board spot {} at {} {} {} {}", spot.number(), spot.world(), spot.x(), spot.y(), spot.z());
        load();
        refresh();
    }

    /** Worker: forgets the place, its blocks stay as they are. */
    public static boolean remove(int number) {
        boolean removed = Database.update("DELETE FROM wanted_board WHERE spot = ?", number) > 0;
        if (removed) load();
        return removed;
    }


    /* refresh */

    // worker: the most wanted players, then the blocks on the main thread
    private static void refresh() {
        if (spots.isEmpty()) return;
        List<Wanted> wanted;
        try {
            wanted = mostWanted();
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().warn("Wanted board could not be loaded: {}", e.toString());
            return;
        }
        Tasks.sync(() -> draw(wanted));
    }

    // highest bounty first; on a tie wanted until caught, then the longest remaining time
    private static List<Wanted> mostWanted() {
        List<Wanted> wanted = new ArrayList<>();
        for (PlayerRepository.PlayerData data : PlayerRepository.getWantedPlayers()) {
            String name = Bukkit.getOfflinePlayer(data.uuid()).getName();
            int bounty = (int) Math.min(Integer.MAX_VALUE, Loans.bounty(data.uuid(), data.jailEnd()) + Bounties.pledged(data.uuid()));
            wanted.add(new Wanted(data.uuid(), name == null ? "Unbekannt" : name, bounty, data.jailEnd()));
        }
        wanted.sort(Comparator.comparingInt(Wanted::bounty).reversed()
                .thenComparing(entry -> entry.wantedEnd() != 0)
                .thenComparing(Comparator.comparingLong(Wanted::wantedEnd).reversed()));
        return wanted.subList(0, Math.min(SPOTS, wanted.size()));
    }

    // compares with the blocks themselves, so a head or sign that was replaced gets drawn again with the next refresh
    private static void draw(List<Wanted> wanted) {
        for (Spot spot : spots.values()) {
            Wanted entry = spot.number() <= wanted.size() ? wanted.get(spot.number() - 1) : null;
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
    private static boolean showsHead(Block block, @Nullable Wanted entry) {
        if (!(block.getState() instanceof Skull skull)) return true;
        Material type = block.getType();
        if (entry == null) return type == Material.SKELETON_SKULL || type == Material.SKELETON_WALL_SKULL;
        if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) return false;
        ResolvableProfile profile = skull.getProfile();
        return profile != null && entry.uuid().equals(profile.uuid());
    }

    private static boolean showsName(Block block, int line, @Nullable Wanted entry) {
        if (!(block.getState() instanceof Sign sign)) return true;
        String text = Text.legacy(sign.getSide(Side.FRONT).line(line));
        return entry == null ? text.isBlank() : text.equals(entry.name());
    }

    private static void drawHead(Block block, @Nullable Wanted entry) {
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

    // player head for a wanted player, skeleton skull for a free place; on the wall or standing as before
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

    private static void drawName(Block block, int line, @Nullable Wanted entry) {
        if (!(block.getState() instanceof Sign sign)) return;
        sign.getSide(Side.FRONT).line(line, entry == null ? Component.empty() : Component.text(entry.name()));
        // nobody edits the board by hand
        sign.setWaxed(true);
        sign.update(true, false);
    }

}
