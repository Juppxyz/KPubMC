package xyz.jupp.minecraft.building;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Hangable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A building template of the Bauamt: the blocks inside the box the admin selected, trimmed to its contents. A copy
 * counts when every cell of the box matches, material and orientation, and every empty cell of the template is empty
 * in the copy too (nothing in the gaps). Blocks outside the box do not matter. The copy may be turned by any quarter
 * and mirrored, so one template serves the left and the right lane of a road.
 * Cushions are entities, not blocks: the template remembers their cell and colour, a copy needs the same cushions
 * in the same cells and no other cushion inside the box.
 */
public final class Blueprint {

    public static final int MAX_EDGE = 48;
    public static final int MAX_CELLS = 8000;
    public static final int MIN_BLOCKS = 8;

    /** A block of the template, relative to the min corner of its box. */
    public record Cell(int dx, int dy, int dz, @NotNull BlockData data) {
        public Material material() {
            return data.getMaterial();
        }
    }

    /** An empty cell of the box. */
    public record Gap(int dx, int dy, int dz) {}

    /** A cushion (an entity) in an empty cell of the box. */
    public record Prop(int dx, int dy, int dz, @NotNull DyeColor color) {
        public Material material() {
            return cushionMaterial(color);
        }
    }

    /**
     * The template turned and mirrored. Offsets are relative to the min corner of the turned box; the blocks come
     * rarest material first, so a wrong candidate fails after one or two reads.
     */
    public record Variant(@NotNull StructureRotation rotation, boolean mirrored, int sizeX, int sizeY, int sizeZ,
                          @NotNull List<Cell> blocks, @NotNull List<Gap> gaps, @NotNull List<Prop> props, @NotNull BlockData @NotNull [] grid) {
        /** Everything a copy has to get right. */
        public int cells() {
            return blocks.size() + gaps.size() + props.size();
        }

        /** The block the template wants in the cell, null for a gap or outside the box. */
        public @Nullable BlockData data(int dx, int dy, int dz) {
            if (dx < 0 || dx >= sizeX || dy < 0 || dy >= sizeY || dz < 0 || dz >= sizeZ) return null;
            return grid[(dx * sizeY + dy) * sizeZ + dz];
        }

        /** Whether the template wants a cushion in the cell. */
        public boolean prop(int dx, int dy, int dz) {
            for (Prop prop : props) {
                if (prop.dx() == dx && prop.dy() == dy && prop.dz() == dz) return true;
            }
            return false;
        }
    }

    /** The contents of a selection, trimmed to the box around them. */
    public record Capture(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, @NotNull List<Cell> blocks, @NotNull List<Prop> props) {}

    private static final Map<DyeColor, Material> CUSHIONS = new EnumMap<>(DyeColor.class);

    static {
        for (DyeColor color : DyeColor.values()) {
            Material material = Material.matchMaterial(color.name() + "_CUSHION");
            CUSHIONS.put(color, material == null ? Material.WHITE_WOOL : material);
        }
    }

    private final int id;
    private final String name;
    private final int reward;
    private final boolean enabled;
    private final String world;
    private final int originX, originY, originZ;
    private final int sizeX, sizeY, sizeZ;
    private final List<Cell> blocks;
    private final List<Prop> props;
    private final List<Variant> variants;
    private final Map<Material, Integer> materials;
    private final Material icon;

    Blueprint(int id, @NotNull String name, int reward, boolean enabled, @NotNull String world, int originX, int originY, int originZ,
              int sizeX, int sizeY, int sizeZ, @NotNull List<Cell> blocks, @NotNull List<Prop> props) {
        this.id = id;
        this.name = name;
        this.reward = reward;
        this.enabled = enabled;
        this.world = world;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = List.copyOf(blocks);
        this.props = List.copyOf(props);
        this.materials = count(blocks, props);
        this.icon = materials.keySet().stream().filter(Material::isItem).findFirst().orElse(Material.BRICKS);
        this.variants = variants(sizeX, sizeY, sizeZ, blocks, props);
    }

    public int id() {
        return id;
    }

    public @NotNull String name() {
        return name;
    }

    public int reward() {
        return reward;
    }

    public boolean enabled() {
        return enabled;
    }

    public @NotNull String world() {
        return world;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int blockCount() {
        return blocks.size();
    }

    public int propCount() {
        return props.size();
    }

    public @NotNull List<Variant> variants() {
        return variants;
    }

    /** The variant a build was stored with, null if the template changed since. */
    public @Nullable Variant variant(@NotNull StructureRotation rotation, boolean mirrored) {
        for (Variant variant : variants) {
            if (variant.rotation() == rotation && variant.mirrored() == mirrored) return variant;
        }
        return null;
    }

    /** Material and count, most frequent first (for the material list); cushions by their item. */
    public @NotNull Map<Material, Integer> materials() {
        return materials;
    }

    public @NotNull Material icon() {
        return icon;
    }


    /* cushions */

    /** The item of a cushion colour. */
    public static @NotNull Material cushionMaterial(@Nullable DyeColor color) {
        return CUSHIONS.get(Objects.requireNonNullElse(color, DyeColor.WHITE));
    }

    /** The colour of a cushion item, null for anything else. */
    public static @Nullable DyeColor cushionColor(@NotNull Material material) {
        for (Map.Entry<DyeColor, Material> entry : CUSHIONS.entrySet()) {
            if (entry.getValue() == material) return entry.getKey();
        }
        return null;
    }

    /** Main thread: the cushions inside a box, by cell. */
    public static @NotNull Map<Long, DyeColor> cushions(@NotNull World world, int ox, int oy, int oz, int sizeX, int sizeY, int sizeZ) {
        Map<Long, DyeColor> found = new HashMap<>();
        BoundingBox box = new BoundingBox(ox, oy, oz, ox + sizeX, oy + sizeY, oz + sizeZ);
        for (Entity entity : world.getNearbyEntities(box, entity -> entity instanceof Cushion)) {
            Location at = entity.getLocation();
            int dx = at.getBlockX() - ox, dy = at.getBlockY() - oy, dz = at.getBlockZ() - oz;
            // the search matches by the entity's body, a cushion just outside the box can touch it
            if (dx < 0 || dx >= sizeX || dy < 0 || dy >= sizeY || dz < 0 || dz >= sizeZ) continue;
            found.putIfAbsent(key(dx, dy, dz), Objects.requireNonNullElse(((Cushion) entity).getColor(), DyeColor.WHITE));
        }
        return found;
    }

    private static long key(int dx, int dy, int dz) {
        return ((long) dx << 42) | ((long) dy << 21) | dz;
    }


    /* matching */

    /** Cells of the box at this origin that do not match; stops counting once more than stopAfter were found. */
    public static int mismatches(@NotNull World world, int ox, int oy, int oz, @NotNull Variant variant, int stopAfter) {
        int bad = 0;
        for (Cell cell : variant.blocks()) {
            if (!same(cell.data(), world.getBlockData(ox + cell.dx(), oy + cell.dy(), oz + cell.dz()))) {
                if (++bad > stopAfter) return bad;
            }
        }
        for (Gap gap : variant.gaps()) {
            if (!empty(world.getType(ox + gap.dx(), oy + gap.dy(), oz + gap.dz()))) {
                if (++bad > stopAfter) return bad;
            }
        }
        // last, once the blocks fit: every cushion of the template in its cell, no other cushion in the box
        Map<Long, DyeColor> cushions = cushions(world, ox, oy, oz, variant.sizeX(), variant.sizeY(), variant.sizeZ());
        for (Prop prop : variant.props()) {
            if (cushions.remove(key(prop.dx(), prop.dy(), prop.dz())) != prop.color()) {
                if (++bad > stopAfter) return bad;
            }
        }
        bad += cushions.size();
        return bad;
    }

    /** Diagnostic, main thread: the first cells at this origin that do not match, as readable lines (world coordinates). */
    public static @NotNull List<String> explain(@NotNull World world, int ox, int oy, int oz, @NotNull Variant variant, int limit) {
        List<String> lines = new ArrayList<>();
        for (Cell cell : variant.blocks()) {
            int x = ox + cell.dx(), y = oy + cell.dy(), z = oz + cell.dz();
            BlockData actual = world.getBlockData(x, y, z);
            if (same(cell.data(), actual)) continue;
            lines.add(x + " / " + y + " / " + z + ": erwartet " + cell.data().getAsString(true) + ", gefunden " + actual.getAsString(true));
            if (lines.size() >= limit) return lines;
        }
        for (Gap gap : variant.gaps()) {
            int x = ox + gap.dx(), y = oy + gap.dy(), z = oz + gap.dz();
            Material actual = world.getType(x, y, z);
            if (empty(actual)) continue;
            lines.add(x + " / " + y + " / " + z + ": sollte leer sein, gefunden " + actual.name().toLowerCase(java.util.Locale.ROOT));
            if (lines.size() >= limit) return lines;
        }
        Map<Long, DyeColor> cushions = cushions(world, ox, oy, oz, variant.sizeX(), variant.sizeY(), variant.sizeZ());
        for (Prop prop : variant.props()) {
            DyeColor found = cushions.remove(key(prop.dx(), prop.dy(), prop.dz()));
            if (found == prop.color()) continue;
            lines.add((ox + prop.dx()) + " / " + (oy + prop.dy()) + " / " + (oz + prop.dz()) + ": Kissen " + prop.color() + " erwartet, "
                    + (found == null ? "keins" : found) + " gefunden");
            if (lines.size() >= limit) return lines;
        }
        cushions.forEach((key, color) -> {
            if (lines.size() >= limit) return;
            int dx = (int) (key >> 42), dy = (int) ((key >> 21) & 0x1FFFFF), dz = (int) (key & 0x1FFFFF);
            lines.add((ox + dx) + " / " + (oy + dy) + " / " + (oz + dz) + ": Kissen " + color + " zu viel");
        });
        return lines;
    }

    /** Empty for a template: air, or the snow that falls on a build in winter. */
    static boolean empty(@NotNull Material material) {
        return material.isAir() || material == Material.SNOW;
    }

    // material and the orientation a builder chooses; states like lit, open, powered or waterlogged do not matter
    static boolean same(@NotNull BlockData expected, @NotNull BlockData actual) {
        if (expected.getMaterial() != actual.getMaterial()) return false;
        if (expected instanceof Directional e && actual instanceof Directional a && e.getFacing() != a.getFacing()) return false;
        if (expected instanceof Orientable e && actual instanceof Orientable a && e.getAxis() != a.getAxis()) return false;
        if (expected instanceof Bisected e && actual instanceof Bisected a && e.getHalf() != a.getHalf()) return false;
        if (expected instanceof Stairs e && actual instanceof Stairs a && e.getShape() != a.getShape()) return false;
        if (expected instanceof Slab e && actual instanceof Slab a && e.getType() != a.getType()) return false;
        if (expected instanceof Rail e && actual instanceof Rail a && e.getShape() != a.getShape()) return false;
        if (expected instanceof Rotatable e && actual instanceof Rotatable a && e.getRotation() != a.getRotation()) return false;
        if (expected instanceof FaceAttachable e && actual instanceof FaceAttachable a && e.getAttachedFace() != a.getAttachedFace()) return false;
        if (expected instanceof Hangable e && actual instanceof Hangable a && e.isHanging() != a.isHanging()) return false;
        return true;
    }

    /** Whether every chunk of the box is loaded with its entities (never loads one). */
    public static boolean chunksLoaded(@NotNull World world, int x, int z, int sizeX, int sizeZ) {
        for (int cx = x >> 4; cx <= (x + sizeX - 1) >> 4; cx++) {
            for (int cz = z >> 4; cz <= (z + sizeZ - 1) >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz) || !world.getChunkAt(cx, cz).isEntitiesLoaded()) return false;
            }
        }
        return true;
    }


    /* the turned copies */

    private static List<Variant> variants(int sizeX, int sizeY, int sizeZ, List<Cell> blocks, List<Prop> props) {
        Map<Material, Integer> counts = new HashMap<>();
        for (Cell cell : blocks) counts.merge(cell.material(), 1, Integer::sum);
        // a symmetric template turns into the same thing twice, that copy is dropped
        Map<String, Variant> unique = new LinkedHashMap<>();
        for (boolean mirrored : new boolean[]{false, true}) {
            for (StructureRotation rotation : StructureRotation.values()) {
                int turns = switch (rotation) {
                    case NONE -> 0;
                    case CLOCKWISE_90 -> 1;
                    case CLOCKWISE_180 -> 2;
                    case COUNTERCLOCKWISE_90 -> 3;
                };
                int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (int[] corner : new int[][]{{0, 0}, {sizeX - 1, 0}, {0, sizeZ - 1}, {sizeX - 1, sizeZ - 1}}) {
                    int[] turned = turn(corner[0], corner[1], mirrored, turns);
                    minX = Math.min(minX, turned[0]);
                    maxX = Math.max(maxX, turned[0]);
                    minZ = Math.min(minZ, turned[1]);
                    maxZ = Math.max(maxZ, turned[1]);
                }
                int turnedX = maxX - minX + 1, turnedZ = maxZ - minZ + 1;
                BlockData[] grid = new BlockData[turnedX * sizeY * turnedZ];
                List<Cell> turnedBlocks = new ArrayList<>(blocks.size());
                for (Cell cell : blocks) {
                    int[] turned = turn(cell.dx(), cell.dz(), mirrored, turns);
                    BlockData data = cell.data().clone();
                    if (mirrored) data.mirror(Mirror.LEFT_RIGHT);
                    if (rotation != StructureRotation.NONE) data.rotate(rotation);
                    int x = turned[0] - minX, z = turned[1] - minZ;
                    turnedBlocks.add(new Cell(x, cell.dy(), z, data));
                    grid[(x * sizeY + cell.dy()) * turnedZ + z] = data;
                }
                turnedBlocks.sort(Comparator.comparingInt(cell -> counts.get(cell.material())));
                List<Prop> turnedProps = new ArrayList<>(props.size());
                for (Prop prop : props) {
                    int[] turned = turn(prop.dx(), prop.dz(), mirrored, turns);
                    turnedProps.add(new Prop(turned[0] - minX, prop.dy(), turned[1] - minZ, prop.color()));
                }
                // a cushion sits in an empty cell: the cell stays a gap, the cushion is checked on top
                List<Gap> gaps = new ArrayList<>();
                for (int x = 0; x < turnedX; x++) {
                    for (int y = 0; y < sizeY; y++) {
                        for (int z = 0; z < turnedZ; z++) {
                            if (grid[(x * sizeY + y) * turnedZ + z] == null) gaps.add(new Gap(x, y, z));
                        }
                    }
                }
                Variant variant = new Variant(rotation, mirrored, turnedX, sizeY, turnedZ,
                        List.copyOf(turnedBlocks), List.copyOf(gaps), List.copyOf(turnedProps), grid);
                unique.putIfAbsent(signature(variant), variant);
            }
        }
        return List.copyOf(unique.values());
    }

    // the mirror flips z (north <-> south, like Mirror.LEFT_RIGHT); a quarter turn clockwise seen from above: (x, z) -> (-z, x)
    private static int[] turn(int x, int z, boolean mirrored, int turns) {
        if (mirrored) z = -z;
        for (int i = 0; i < turns; i++) {
            int turnedX = -z;
            z = x;
            x = turnedX;
        }
        return new int[]{x, z};
    }

    private static String signature(Variant variant) {
        List<String> lines = new ArrayList<>(variant.blocks().size() + variant.props().size());
        for (Cell cell : variant.blocks()) lines.add(cell.dx() + "," + cell.dy() + "," + cell.dz() + ":" + cell.data().getAsString());
        for (Prop prop : variant.props()) lines.add(prop.dx() + "," + prop.dy() + "," + prop.dz() + ":@" + prop.color());
        lines.sort(Comparator.naturalOrder());
        return variant.sizeX() + "x" + variant.sizeY() + "x" + variant.sizeZ() + "|" + String.join("\n", lines);
    }

    private static Map<Material, Integer> count(List<Cell> blocks, List<Prop> props) {
        Map<Material, Integer> counts = new HashMap<>();
        for (Cell cell : blocks) counts.merge(cell.material(), 1, Integer::sum);
        for (Prop prop : props) counts.merge(prop.material(), 1, Integer::sum);
        Map<Material, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed().thenComparing(entry -> entry.getKey().name()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }


    /* reading a selection, storing */

    /** Main thread: the blocks and cushions of a box, trimmed to the box around them; null when the selection is empty. */
    public static @Nullable Capture capture(@NotNull World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        List<int[]> found = new ArrayList<>();
        List<BlockData> data = new ArrayList<>();
        int tx1 = Integer.MAX_VALUE, ty1 = Integer.MAX_VALUE, tz1 = Integer.MAX_VALUE;
        int tx2 = Integer.MIN_VALUE, ty2 = Integer.MIN_VALUE, tz2 = Integer.MIN_VALUE;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (empty(world.getType(x, y, z))) continue;
                    found.add(new int[]{x, y, z});
                    data.add(world.getBlockData(x, y, z));
                    tx1 = Math.min(tx1, x);
                    ty1 = Math.min(ty1, y);
                    tz1 = Math.min(tz1, z);
                    tx2 = Math.max(tx2, x);
                    ty2 = Math.max(ty2, y);
                    tz2 = Math.max(tz2, z);
                }
            }
        }
        List<int[]> cushionCells = new ArrayList<>();
        List<DyeColor> colors = new ArrayList<>();
        cushions(world, minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1).forEach((key, color) -> {
            int x = minX + (int) (key >> 42), y = minY + (int) ((key >> 21) & 0x1FFFFF), z = minZ + (int) (key & 0x1FFFFF);
            cushionCells.add(new int[]{x, y, z});
            colors.add(color);
        });
        for (int[] position : cushionCells) {
            tx1 = Math.min(tx1, position[0]);
            ty1 = Math.min(ty1, position[1]);
            tz1 = Math.min(tz1, position[2]);
            tx2 = Math.max(tx2, position[0]);
            ty2 = Math.max(ty2, position[1]);
            tz2 = Math.max(tz2, position[2]);
        }
        if (found.isEmpty() && cushionCells.isEmpty()) return null;
        List<Cell> blocks = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            int[] position = found.get(i);
            blocks.add(new Cell(position[0] - tx1, position[1] - ty1, position[2] - tz1, data.get(i)));
        }
        List<Prop> props = new ArrayList<>(cushionCells.size());
        for (int i = 0; i < cushionCells.size(); i++) {
            int[] position = cushionCells.get(i);
            props.add(new Prop(position[0] - tx1, position[1] - ty1, position[2] - tz1, colors.get(i)));
        }
        return new Capture(tx1, ty1, tz1, tx2 - tx1 + 1, ty2 - ty1 + 1, tz2 - tz1 + 1, blocks, props);
    }

    /** One line per block "dx dy dz blockdata", per cushion "dx dy dz @COLOUR". */
    static @NotNull String serialize(@NotNull List<Cell> blocks, @NotNull List<Prop> props) {
        StringBuilder text = new StringBuilder();
        for (Cell cell : blocks) {
            text.append(cell.dx()).append(' ').append(cell.dy()).append(' ').append(cell.dz()).append(' ')
                    .append(cell.data().getAsString()).append('\n');
        }
        for (Prop prop : props) {
            text.append(prop.dx()).append(' ').append(prop.dy()).append(' ').append(prop.dz()).append(" @").append(prop.color().name()).append('\n');
        }
        return text.toString();
    }

    /** The stored blocks and cushions; @throws IllegalArgumentException for a block this server version does not know. */
    static @NotNull Contents parse(@NotNull String text) {
        List<Cell> blocks = new ArrayList<>();
        List<Prop> props = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split(" ", 4);
            if (parts.length < 4) throw new IllegalArgumentException("broken line: " + line);
            int dx = Integer.parseInt(parts[0]), dy = Integer.parseInt(parts[1]), dz = Integer.parseInt(parts[2]);
            if (parts[3].startsWith("@")) props.add(new Prop(dx, dy, dz, DyeColor.valueOf(parts[3].substring(1))));
            else blocks.add(new Cell(dx, dy, dz, Bukkit.createBlockData(parts[3])));
        }
        return new Contents(blocks, props);
    }

    record Contents(@NotNull List<Cell> blocks, @NotNull List<Prop> props) {}

}
