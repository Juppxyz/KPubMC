package xyz.jupp.minecraft.building;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Bauamt's building plan: a map showing one layer of a template from above, the way the admin built it (a copy
 * may still be turned or mirrored). A right click with the plan turns to the next layer (sneaking: back), a left click
 * on a block checks the copy there ({@link PlanCheck}).
 * Every layer is a map of its own, so a plan in an item frame shows everyone the same. The map ids are stored; the
 * maps are locked and empty on disk, the plugin draws them again after every start.
 */
public final class Bauplan {

    private Bauplan() {}

    private static final NamespacedKey TEMPLATE = new NamespacedKey(Main.getInstance(), "bauplan");
    private static final NamespacedKey LAYER = new NamespacedKey(Main.getInstance(), "bauplan_layer");
    // the centre of a plan's map marks it as one: vanilla maps are centred on a grid (…64), never on these values
    private static final int MARK_X = 1_999_999_937;
    private static final int LAYERS_PER_TEMPLATE = 64;
    private static final long FLIP_COOLDOWN_MILLIS = 200;

    // main thread: template and layer -> map id
    private static final Map<Long, Integer> maps = new HashMap<>();
    private static final Map<UUID, Long> flips = new HashMap<>();


    /* the maps */

    /** Main thread, in onEnable after the templates: the stored maps get their drawing back. */
    static void load() {
        maps.clear();
        List<int[]> rows = Database.query("SELECT template_id, layer, map_id FROM bauamt_maps",
                row -> new int[]{row.getInt("template_id"), row.getInt("layer"), row.getInt("map_id")});
        List<int[]> stale = new ArrayList<>();
        for (int[] row : rows) {
            Blueprint template = Bauamt.template(row[0]);
            MapView view = Bukkit.getMap(row[2]);
            // a reset world may have given the id to another map: that one stays untouched, the plan gets a new one
            if (template == null || row[1] >= template.sizeY() || !ours(view, row[0], row[1])) {
                stale.add(row);
                continue;
            }
            draw(view, template, row[1]);
            maps.put(key(row[0], row[1]), row[2]);
        }
        if (stale.isEmpty()) return;
        Tasks.async(() -> {
            for (int[] row : stale) {
                Database.update("DELETE FROM bauamt_maps WHERE template_id = ? AND layer = ? AND map_id = ?", row[0], row[1], row[2]);
            }
        });
    }

    private static long key(int templateId, int layer) {
        return (long) templateId * LAYERS_PER_TEMPLATE + layer;
    }

    private static int mark(int templateId, int layer) {
        return (int) key(templateId, layer);
    }

    private static boolean ours(@Nullable MapView view, int templateId, int layer) {
        return view != null && view.isLocked() && view.getCenterX() == MARK_X && view.getCenterZ() == mark(templateId, layer);
    }

    /** Main thread: the map of a layer, made the first time anybody needs it. */
    static @NotNull MapView view(@NotNull Blueprint template, int layer) {
        Integer id = maps.get(key(template.id(), layer));
        if (id != null) {
            MapView view = Bukkit.getMap(id);
            if (ours(view, template.id(), layer)) return view;
        }
        MapView view = Bukkit.createMap(Bukkit.getWorlds().getFirst());
        view.setScale(MapView.Scale.CLOSEST);
        view.setCenterX(MARK_X);
        view.setCenterZ(mark(template.id(), layer));
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        draw(view, template, layer);
        maps.put(key(template.id(), layer), view.getId());
        int templateId = template.id(), mapId = view.getId();
        Tasks.async(() -> {
            try {
                Database.update("""
                        INSERT INTO bauamt_maps (template_id, layer, map_id) VALUES (?, ?, ?)
                        ON CONFLICT (template_id, layer) DO UPDATE SET map_id = EXCLUDED.map_id""", templateId, layer, mapId);
            } catch (RuntimeException e) {
                // the template was deleted meanwhile; the map is drawn until the next start anyway
                Main.getInstance().getSLF4JLogger().warn("Could not store the plan map {} of template {}", mapId, templateId, e);
            }
        });
        return view;
    }

    // the plugin's drawing instead of the vanilla one
    private static void draw(MapView view, Blueprint template, int layer) {
        for (MapRenderer renderer : List.copyOf(view.getRenderers())) view.removeRenderer(renderer);
        view.addRenderer(new PlanRenderer(template, layer));
    }


    /* the item */

    /** Main thread: the plan of a template, open at a layer (0 = the bottom one). */
    static @NotNull ItemStack item(@NotNull Blueprint template, int layer) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view(template, layer));
        meta.customName(Text.of("§6Bauplan §e" + template.name()));
        meta.lore(Text.lore(List.of(
                "§7Ebene §f" + (layer + 1) + " §7von §f" + template.sizeY() + " §7(1 ist ganz unten)",
                "§7Größe: §f" + Bauamt.size(template) + " §7Blöcke",
                "§7Gedreht oder gespiegelt nachbauen zählt auch.",
                "",
                "§e» Rechtsklick: nächste Ebene",
                "§e» Schleichen + Rechtsklick: Ebene zurück",
                "§e» Linksklick auf deinen Bau: prüfen")));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(TEMPLATE, PersistentDataType.INTEGER, template.id());
        data.set(LAYER, PersistentDataType.INTEGER, layer);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isPlan(@Nullable ItemStack item) {
        return item != null && item.getType() == Material.FILLED_MAP
                && item.getPersistentDataContainer().has(TEMPLATE, PersistentDataType.INTEGER);
    }

    /** The template id of a plan, -1 for any other item. */
    static int templateId(@NotNull ItemStack item) {
        Integer id = item.getPersistentDataContainer().get(TEMPLATE, PersistentDataType.INTEGER);
        return id == null ? -1 : id;
    }

    private static int layer(@NotNull ItemStack item) {
        Integer layer = item.getPersistentDataContainer().get(LAYER, PersistentDataType.INTEGER);
        return layer == null ? 0 : layer;
    }

    /** Main thread: hands the player the plan unless they already carry one of the template; false if they do. */
    static boolean give(@NotNull Player player, @NotNull Blueprint template) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isPlan(item) && templateId(item) == template.id()) return false;
        }
        for (ItemStack rest : player.getInventory().addItem(item(template, 0)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        return true;
    }

    /** Main thread: turns the plan in the player's main hand to the next layer, or back to the one below. */
    static void flip(@NotNull Player player, boolean back) {
        long now = System.currentTimeMillis();
        Long last = flips.get(player.getUniqueId());
        // a right click on a block sends a click into the air after it: one page per click
        if (last != null && now - last < FLIP_COOLDOWN_MILLIS) return;
        flips.put(player.getUniqueId(), now);
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isPlan(item)) return;
        Blueprint template = Bauamt.template(templateId(item));
        if (template == null) {
            player.sendActionBar(Text.of("§cDiese Vorlage gibt es beim Bauamt nicht mehr."));
            return;
        }
        int layers = template.sizeY();
        if (layers <= 1) {
            player.sendActionBar(Text.of("§7Dieser Bauplan hat nur eine Ebene."));
            return;
        }
        int layer = Math.floorMod(layer(item) + (back ? -1 : 1), layers);
        ItemStack turned = item(template, layer);
        turned.setAmount(item.getAmount());
        player.getInventory().setItemInMainHand(turned);
        player.sendActionBar(Text.of("§6Bauplan §e" + template.name() + " §7» §fEbene " + (layer + 1) + " §7von §f" + layers));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
    }

    static void forget(@NotNull UUID player) {
        flips.remove(player);
    }


    /* the drawing: a blueprint, the box as a grid, every block in its map colour */

    private static final Color PAPER = new Color(28, 42, 104);
    // an empty cell inside the box: it has to stay empty in the copy
    private static final Color CELL = new Color(44, 66, 148);
    private static final Color GRID = new Color(72, 102, 182);
    private static final Color FRAME = new Color(225, 232, 255);
    // blocks without a map colour (glass, torches, rails ...)
    private static final Color NO_COLOUR = new Color(170, 200, 215);
    // map palette index 34: snow (colour 8) in its brightest shade, pure white
    private static final String WHITE_TEXT = "§34;";
    private static final int TOP = 14, AREA_WIDTH = 122, AREA_HEIGHT = 110, MAX_SCALE = 16;

    /** Draws its layer once; the map is locked, nothing else paints on it. */
    private static final class PlanRenderer extends MapRenderer {
        private final Blueprint template;
        private final int layer;
        private boolean drawn;

        PlanRenderer(Blueprint template, int layer) {
            super(false);
            this.template = template;
            this.layer = layer;
        }

        @Override
        public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
            if (drawn) return;
            drawn = true;
            paint(canvas, template, layer);
        }
    }

    private static void paint(MapCanvas canvas, Blueprint template, int layer) {
        fill(canvas, 0, 0, 128, 128, PAPER);
        String page = (layer + 1) + "/" + template.sizeY();
        int pageWidth = MinecraftFont.Font.getWidth(page);
        canvas.drawText(2, 2, MinecraftFont.Font, WHITE_TEXT + fit(template.name(), 128 - pageWidth - 8));
        canvas.drawText(126 - pageWidth, 2, MinecraftFont.Font, WHITE_TEXT + page);
        fill(canvas, 0, 11, 128, 1, GRID);

        Blueprint.Variant plan = template.original();
        int sizeX = plan.sizeX(), sizeZ = plan.sizeZ();
        // as big as it fits below the title; north is up, like on every map
        int scale = Math.max(1, Math.min(MAX_SCALE, Math.min(AREA_WIDTH / sizeX, AREA_HEIGHT / sizeZ)));
        int width = sizeX * scale, height = sizeZ * scale;
        int left = (128 - width) / 2, top = TOP + (AREA_HEIGHT + 2 - height) / 2;
        outline(canvas, left - 1, top - 1, width + 2, height + 2, FRAME);
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                int x = left + dx * scale, y = top + dz * scale;
                BlockData data = plan.data(dx, layer, dz);
                if (data == null) emptyCell(canvas, x, y, scale);
                else block(canvas, x, y, scale, data);
            }
        }
        // cushions: a small square of their colour in the middle of the cell
        for (Blueprint.Prop prop : plan.props()) {
            if (prop.dy() != layer) continue;
            int inner = Math.max(1, scale / 2), offset = (scale - inner) / 2;
            fill(canvas, left + prop.dx() * scale + offset, top + prop.dz() * scale + offset, inner, inner,
                    new Color(prop.color().getColor().asRGB()));
        }
    }

    // grid lines right and below, so the blocks of a row can be counted
    private static void emptyCell(MapCanvas canvas, int x, int y, int scale) {
        fill(canvas, x, y, scale, scale, CELL);
        if (scale < 4) return;
        fill(canvas, x + scale - 1, y, 1, scale, GRID);
        fill(canvas, x, y + scale - 1, scale, 1, GRID);
    }

    private static void block(MapCanvas canvas, int x, int y, int scale, BlockData data) {
        int rgb = data.getMapColor().asRGB();
        Color color = rgb == 0 ? NO_COLOUR : new Color(rgb);
        fill(canvas, x, y, scale, scale, color);
        if (scale >= 4) outline(canvas, x, y, scale, scale, shade(color, 0.6));
        // which way it faces (stairs, furnaces, chests ...): a bar along that side
        if (scale < 6 || !(data instanceof Directional directional)) return;
        int bar = Math.max(1, scale / 5), length = Math.max(2, scale / 2), from = (scale - length) / 2;
        Color mark = brightness(color) > 110 ? shade(color, 0.35) : FRAME;
        switch (directional.getFacing()) {
            case NORTH -> fill(canvas, x + from, y + 1, length, bar, mark);
            case SOUTH -> fill(canvas, x + from, y + scale - 1 - bar, length, bar, mark);
            case WEST -> fill(canvas, x + 1, y + from, bar, length, mark);
            case EAST -> fill(canvas, x + scale - 1 - bar, y + from, bar, length, mark);
            default -> {}
        }
    }

    private static void fill(MapCanvas canvas, int x, int y, int width, int height, Color color) {
        for (int px = Math.max(0, x); px < Math.min(128, x + width); px++) {
            for (int py = Math.max(0, y); py < Math.min(128, y + height); py++) {
                canvas.setPixelColor(px, py, color);
            }
        }
    }

    private static void outline(MapCanvas canvas, int x, int y, int width, int height, Color color) {
        fill(canvas, x, y, width, 1, color);
        fill(canvas, x, y + height - 1, width, 1, color);
        fill(canvas, x, y, 1, height, color);
        fill(canvas, x + width - 1, y, 1, height, color);
    }

    private static Color shade(Color color, double factor) {
        return new Color((int) (color.getRed() * factor), (int) (color.getGreen() * factor), (int) (color.getBlue() * factor));
    }

    private static int brightness(Color color) {
        return (color.getRed() * 3 + color.getGreen() * 6 + color.getBlue()) / 10;
    }

    // the map font has no ß and takes § as a colour code; cut to the width
    private static String fit(String text, int maxWidth) {
        StringBuilder safe = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '§') continue;
            String part = c == 'ß' ? "ss" : String.valueOf(c);
            safe.append(MinecraftFont.Font.isValid(part) ? part : "?");
        }
        String result = safe.toString();
        while (!result.isEmpty() && MinecraftFont.Font.getWidth(result) > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

}
