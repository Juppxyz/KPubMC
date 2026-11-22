package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import xyz.jupp.minecraft.Main;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Floating items from /hover that spin (marked in their PDC). Once a STEP_TICKS the server turns each loaded one by a
 * quarter and the client glides there (interpolation), so it spins evenly with one small update per item and step.
 * Only items in loaded chunks are known (EntitiesLoadEvent/EntitiesUnloadEvent), main thread only.
 */
public final class HoverSpin implements Listener {

    private static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "hover_spin");
    private static final int STEP_TICKS = 20;
    // a quarter turn per step (the client takes the short way, so a step must stay under half a turn)
    private static final float STEP = (float) (Math.PI / 2);
    private static final Set<ItemDisplay> SPINNING = new HashSet<>();

    /** The listener and the task; items already loaded are picked up once. */
    public static void start() {
        Bukkit.getPluginManager().registerEvents(new HoverSpin(), Main.getInstance());
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (spins(display)) SPINNING.add(display);
            }
        }
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), HoverSpin::step, STEP_TICKS, STEP_TICKS);
    }

    /** Lets the item spin or stops it (it turns back to the front and faces the viewers again). */
    public static void set(@NotNull ItemDisplay display, boolean on) {
        if (on) {
            display.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            // spins around itself, not around the viewer's facing
            display.setBillboard(Display.Billboard.FIXED);
            SPINNING.add(display);
            return;
        }
        display.getPersistentDataContainer().remove(KEY);
        SPINNING.remove(display);
        display.setBillboard(Display.Billboard.VERTICAL);
        Transformation now = display.getTransformation();
        display.setInterpolationDuration(0);
        display.setTransformation(new Transformation(now.getTranslation(), new Quaternionf(), now.getScale(), now.getRightRotation()));
    }

    private static boolean spins(Entity entity) {
        return entity instanceof ItemDisplay && entity.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    private static void step() {
        for (Iterator<ItemDisplay> it = SPINNING.iterator(); it.hasNext(); ) {
            ItemDisplay display = it.next();
            if (!display.isValid()) {
                it.remove();
                continue;
            }
            Transformation now = display.getTransformation();
            Quaternionf turned = new Quaternionf(now.getLeftRotation()).rotateLocalY(STEP).normalize();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(STEP_TICKS);
            display.setTransformation(new Transformation(now.getTranslation(), turned, now.getScale(), now.getRightRotation()));
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (spins(entity)) SPINNING.add((ItemDisplay) entity);
        }
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemDisplay display) SPINNING.remove(display);
        }
    }

}
