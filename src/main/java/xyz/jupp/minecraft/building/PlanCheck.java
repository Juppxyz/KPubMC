package xyz.jupp.minecraft.building;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Was fehlt noch?": a left click with the building plan on a block of a copy finds the placement of the template that
 * fits best, tells the player how many pieces are right and what is wrong, and marks the first cells to fix with
 * glowing blocks only this player sees, for half a minute (a mark goes as soon as the player places or breaks a block
 * in its cell). A complete copy is reported to the Bauamt right away, as if its last block had just been placed.
 */
final class PlanCheck {

    private PlanCheck() {}

    private static final long COOLDOWN_MILLIS = 2_000;
    private static final int MARKS = 12;
    private static final int LINES = 5;
    private static final int MARK_SECONDS = 30;
    private static final Color GLOW_MISSING = Color.fromRGB(0xFFD83D);
    private static final Color GLOW_WRONG = Color.fromRGB(0xFF8A1F);
    private static final Color GLOW_EXTRA = Color.fromRGB(0xFF3030);

    private record Spot(UUID world, int x, int y, int z) {}

    // main thread
    private static final Map<UUID, Long> checks = new HashMap<>();
    private static final Map<UUID, Map<Spot, BlockDisplay>> marks = new HashMap<>();

    /** Main thread: the player hit a block with the plan of this template. */
    static void check(@NotNull Player player, @NotNull Block block, @NotNull Blueprint template) {
        long now = System.currentTimeMillis();
        Long last = checks.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) return;
        checks.put(player.getUniqueId(), now);

        if (!template.materials().containsKey(block.getType())) {
            tell(player, "§fDieser Block kommt im Bauplan §e" + template.name() + " §fnicht vor. Klick mit dem Bauplan auf einen Block deines Baus.");
            return;
        }
        World world = block.getWorld();
        Bauamt.Diagnosis diagnosis = Bauamt.diagnose(world, block.getX(), block.getY(), block.getZ(), template);
        if (diagnosis == null) {
            tell(player, "§fDas konnte gerade nicht geprüft werden. Geh näher an deinen Bau heran und versuch es nochmal.");
            return;
        }
        clear(player.getUniqueId());
        Blueprint.Variant variant = diagnosis.variant();
        List<Blueprint.Problem> problems = Blueprint.problems(world, diagnosis.ox(), diagnosis.oy(), diagnosis.oz(), variant);
        if (problems.isEmpty()) {
            complete(player, world, template, diagnosis);
            return;
        }

        int missing = 0, wrong = 0, extra = 0;
        for (Blueprint.Problem problem : problems) {
            if (problem.missing()) missing++;
            else if (problem.extra()) extra++;
            else wrong++;
        }
        int pieces = variant.pieces();
        int right = pieces - missing - wrong;
        // only the clicked block fits: no copy to speak of, any placement would do
        if (right < 2) {
            tell(player, "§fHier erkenne ich noch keinen §e" + template.name() + "§f. Klick mit dem Bauplan auf einen Block, der schon richtig sitzt.");
            return;
        }

        // what to fix first: wrong and stray pieces, then the missing ones from the bottom up, the nearest first
        Location at = player.getLocation();
        List<Blueprint.Problem> order = new ArrayList<>(problems);
        order.sort(Comparator.comparingInt((Blueprint.Problem problem) -> problem.missing() ? 1 : 0)
                .thenComparingInt(problem -> problem.missing() ? problem.y() : 0)
                .thenComparingDouble(problem -> distanceSquared(at, problem)));

        tell(player, "§fBauplan §e" + template.name() + "§f: §a" + right + " §fvon §a" + pieces + " §fTeilen sitzen richtig.");
        List<String> counts = new ArrayList<>();
        if (missing > 0) counts.add("§f" + missing + " §7" + (missing == 1 ? "fehlt" : "fehlen"));
        if (wrong > 0) counts.add("§f" + wrong + " §7" + (wrong == 1 ? "ist" : "sind") + " falsch");
        if (extra > 0) counts.add("§f" + extra + " §7zu viel im Rahmen (dort bleibt Luft)");
        player.sendMessage(Text.section("§7" + String.join("§7, ", counts) + "."));
        for (int i = 0; i < Math.min(LINES, order.size()); i++) player.sendMessage(line(order.get(i)));
        if (order.size() > LINES) player.sendMessage(Text.section("§7… und " + (order.size() - LINES) + " weitere."));
        int marked = mark(player, world, order.subList(0, Math.min(MARKS, order.size())));
        player.sendMessage(Text.section("§7" + (marked == 1 ? "Die Stelle leuchtet" : "Die ersten " + marked + " Stellen leuchten") + " "
                + MARK_SECONDS + " Sekunden: §egelb §7fehlt, §6orange §7falsch, §crot §7muss weg."));
        if (diagnosis.cutShort()) player.sendMessage(Text.section("§7Die Suche wurde abgekürzt, klick zur Sicherheit noch einen anderen Block an."));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 0.8f);
    }

    // nothing wrong: report the copy like its last block would
    private static void complete(Player player, World world, Blueprint template, Bauamt.Diagnosis diagnosis) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
        if (!template.enabled()) {
            tell(player, "§a§lAlles richtig gebaut! §fFür §e" + template.name() + " §fvergibt das Bauamt gerade aber keine Förderung.");
            return;
        }
        Bauamt.Finished finished = Bauamt.finish(player, world.getName(), template, diagnosis.variant(), diagnosis.ox(), diagnosis.oy(), diagnosis.oz());
        switch (finished.kind()) {
            case REGISTERED -> Bauamt.hint(player, template, finished.build());
            case PENDING -> tell(player, "§a§lAlles richtig gebaut! §fDein §e" + template.name()
                    + " §fist schon gemeldet. Hol dir die Förderung beim §e§lBauamt §fab.");
            case NO_ORDER -> tell(player, "§a§lAlles richtig gebaut! §fGefördert wird aber nur mit Auftrag: nimm beim §eBauamt §feinen an und prüf dann nochmal.");
            // the whole server hears about it
            case REPAIRED -> {}
            case KNOWN -> tell(player, "§a§lAlles richtig! §fDieses Bauwerk kennt das Bauamt schon.");
            case BLOCKED -> tell(player, "§a§lAlles richtig gebaut, §faber es steckt in einem anderen Bauwerk des Bauamts. Das zählt nicht als neue Kopie.");
        }
    }

    private static Component line(Blueprint.Problem problem) {
        Component line = Text.section("§7• §f" + problem.x() + " / " + problem.y() + " / " + problem.z() + "§7: ");
        return switch (problem.fault()) {
            case MISSING -> line.append(name(problem.expected(), NamedTextColor.YELLOW)).append(Text.section(" §7fehlt"));
            case WRONG -> line.append(name(problem.expected(), NamedTextColor.YELLOW)).append(Text.section(" §7statt "))
                    .append(name(problem.actual(), NamedTextColor.WHITE));
            case TURNED -> line.append(name(problem.expected(), NamedTextColor.YELLOW)).append(Text.section(" §7ist falsch ausgerichtet"));
            case EXTRA -> line.append(name(problem.actual(), NamedTextColor.RED)).append(Text.section(" §7muss weg"));
            case CUSHION_MISSING -> line.append(cushion(problem.expectedColor(), NamedTextColor.YELLOW)).append(Text.section(" §7fehlt"));
            case CUSHION_WRONG -> line.append(cushion(problem.expectedColor(), NamedTextColor.YELLOW)).append(Text.section(" §7statt "))
                    .append(cushion(problem.actualColor(), NamedTextColor.WHITE));
            case CUSHION_EXTRA -> line.append(cushion(problem.actualColor(), NamedTextColor.RED)).append(Text.section(" §7muss weg"));
        };
    }

    // the client's own language names the block
    private static Component name(@Nullable BlockData data, NamedTextColor color) {
        return data == null ? Component.text("?", color) : Component.translatable(data.getMaterial(), color);
    }

    private static Component cushion(@Nullable DyeColor color, NamedTextColor textColor) {
        return Component.translatable(Blueprint.cushionMaterial(color), textColor);
    }

    private static double distanceSquared(Location at, Blueprint.Problem problem) {
        double dx = problem.x() + 0.5 - at.getX(), dy = problem.y() + 0.5 - at.getY(), dz = problem.z() + 0.5 - at.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static void tell(Player player, String message) {
        player.sendMessage(Main.getChatPrefix() + message);
    }


    /* the marks */

    // what a mark shows: the block that belongs there (small when the cell is empty, over the wrong one), red glass
    // over a block that has to go; cushions as a small cube of their colour on the floor of the cell
    private record Look(@NotNull BlockData block, float size, @NotNull Color glow, boolean onFloor) {}

    private static int mark(Player player, World world, List<Blueprint.Problem> problems) {
        Map<Spot, BlockDisplay> mine = new HashMap<>();
        for (Blueprint.Problem problem : problems) {
            Spot spot = new Spot(world.getUID(), problem.x(), problem.y(), problem.z());
            if (mine.containsKey(spot)) continue;
            BlockDisplay display = spawn(world, problem);
            player.showEntity(Main.getInstance(), display);
            mine.put(spot, display);
        }
        UUID uuid = player.getUniqueId();
        marks.put(uuid, mine);
        Tasks.syncLater(20L * MARK_SECONDS, () -> {
            // a newer check has marks of its own
            if (marks.get(uuid) == mine) clear(uuid);
        });
        return mine.size();
    }

    private static BlockDisplay spawn(World world, Blueprint.Problem problem) {
        Look look = switch (problem.fault()) {
            case MISSING -> new Look(visible(problem.expected()), 0.6f, GLOW_MISSING, false);
            case WRONG, TURNED -> new Look(visible(problem.expected()), 1.02f, GLOW_WRONG, false);
            case EXTRA -> new Look(Material.RED_STAINED_GLASS.createBlockData(), 1.02f, GLOW_EXTRA, false);
            case CUSHION_MISSING -> new Look(wool(problem.expectedColor()), 0.45f, GLOW_MISSING, true);
            case CUSHION_WRONG -> new Look(wool(problem.expectedColor()), 0.45f, GLOW_WRONG, true);
            case CUSHION_EXTRA -> new Look(Material.RED_STAINED_GLASS.createBlockData(), 0.45f, GLOW_EXTRA, true);
        };
        float shift = (1 - look.size()) / 2;
        Transformation transformation = new Transformation(new Vector3f(shift, look.onFloor() ? 0.02f : shift, shift),
                new AxisAngle4f(), new Vector3f(look.size(), look.size(), look.size()), new AxisAngle4f());
        return world.spawn(new Location(world, problem.x(), problem.y(), problem.z()), BlockDisplay.class, display -> {
            // only the checking player sees it, and it is never saved with the chunk
            display.setVisibleByDefault(false);
            display.setPersistent(false);
            display.setBlock(look.block());
            display.setTransformation(transformation);
            display.setGlowing(true);
            display.setGlowColorOverride(look.glow());
            display.setBrightness(new Display.Brightness(15, 15));
        });
    }

    // water, lava and invisible blocks show nothing as a display: glass in a fitting colour instead
    private static BlockData visible(@Nullable BlockData data) {
        if (data == null) return Material.WHITE_STAINED_GLASS.createBlockData();
        Material material = data.getMaterial();
        if (material == Material.WATER || material == Material.BUBBLE_COLUMN) return Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
        if (material == Material.LAVA) return Material.ORANGE_STAINED_GLASS.createBlockData();
        if (material == Material.LIGHT || material == Material.BARRIER || material == Material.STRUCTURE_VOID) {
            return Material.WHITE_STAINED_GLASS.createBlockData();
        }
        return data;
    }

    private static BlockData wool(@Nullable DyeColor color) {
        Material material = Material.matchMaterial((color == null ? DyeColor.WHITE : color).name() + "_WOOL");
        return (material == null ? Material.WHITE_WOOL : material).createBlockData();
    }

    /** Main thread: removes the player's marks. */
    static void clear(@NotNull UUID player) {
        Map<Spot, BlockDisplay> mine = marks.remove(player);
        if (mine != null) mine.values().forEach(Entity::remove);
    }

    /** Main thread: the player placed or broke a block; a mark in that cell goes. */
    static void changed(@NotNull Player player, @NotNull Block block) {
        Map<Spot, BlockDisplay> mine = marks.get(player.getUniqueId());
        if (mine == null) return;
        BlockDisplay display = mine.remove(new Spot(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        if (display != null) display.remove();
    }

    static void forget(@NotNull UUID player) {
        clear(player);
        checks.remove(player);
    }

}
