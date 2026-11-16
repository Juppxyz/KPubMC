package xyz.jupp.minecraft.perf;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who is behind work the server does later on a plugin's behalf, which a stack sample cannot see: when a plugin changes
 * items, the server compares and sends them in the player's next tick (inventory sync); when a plugin spawns display
 * entities or item frames, the server ticks and tracks them every tick. These are counts, not times:
 * <ul>
 *     <li>slot changes in players' inventories, by the plugin marker (NamespacedKey namespace) on the old or new item,
 *     counted one second in ten (counting makes Paper copy the items, so it must not run all the time);</li>
 *     <li>once a second: players with a plugin's marked items in the hotbar, and open menus of a plugin;</li>
 *     <li>entities a plugin spawned during the measurement (the plugin's code on the stack of the spawn event), and at
 *     the end all entities by plugin marker.</li>
 * </ul>
 * Main thread only; the report worker reads the counts after the measurement ended.
 */
final class PerfCauses implements Listener {

    // the slot changes of items without any plugin marker (vanilla items)
    static final String UNMARKED = "ohne Plugin-Markierung";
    // slot changes are counted in one second of every COUNT_EVERY seconds
    private static final int COUNT_EVERY = 10;
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    // a bound for the stack walks of the spawn counter (a burst of spawns must not cost much)
    private static final int MAX_WALKS_PER_SECOND = 200;
    private long walkSecond;
    private int walks;

    // marker namespace (lower case plugin name) -> plugin name
    private final Map<String, String> namespaces = new HashMap<>();
    final Map<String, long[]> slotChanges = new HashMap<>();
    long allSlotChanges;
    // how long the slot counter was registered in total
    long slotNanos;
    private long slotSince;
    final Map<String, long[]> hotbarPlayers = new HashMap<>();
    final Map<String, long[]> openMenus = new HashMap<>();
    int samples;
    // plugin -> entity type -> spawned
    final Map<String, Map<String, Integer>> spawned = new HashMap<>();
    private final Listener slotCounter = new Listener() {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onSlotChange(PlayerInventorySlotChangeEvent event) {
            Set<String> plugins = new HashSet<>(2);
            addPlugins(event.getOldItemStack(), plugins);
            addPlugins(event.getNewItemStack(), plugins);
            if (plugins.isEmpty()) plugins.add(UNMARKED);
            for (String plugin : plugins) slotChanges.computeIfAbsent(plugin, k -> new long[1])[0]++;
            allSlotChanges++;
        }
    };
    private boolean counting;

    PerfCauses() {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            namespaces.put(plugin.getName().toLowerCase(Locale.ROOT), plugin.getName());
        }
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
    }

    void stop() {
        HandlerList.unregisterAll(this);
        stopCounting();
    }

    private void stopCounting() {
        HandlerList.unregisterAll(slotCounter);
        if (counting) slotNanos += System.nanoTime() - slotSince;
        counting = false;
    }

    /** Slot changes per second for a plugin (over the time the counter was registered). */
    double slotRate(String plugin) {
        return rate(slotChanges.getOrDefault(plugin, new long[1])[0]);
    }

    /** All slot changes per second. */
    double allSlotRate() {
        return rate(allSlotChanges);
    }

    private double rate(long changes) {
        return slotNanos <= 0 ? 0 : changes / (slotNanos / 1e9);
    }

    // once a second
    void sample(long second) {
        samples++;
        boolean count = second % COUNT_EVERY == 1;
        if (count && !counting) {
            Bukkit.getPluginManager().registerEvents(slotCounter, Main.getInstance());
            counting = true;
            slotSince = System.nanoTime();
        } else if (!count && counting) {
            stopCounting();
        }
        Set<String> plugins = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugins.clear();
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < 9; slot++) addPlugins(inventory.getItem(slot), plugins);
            for (String plugin : plugins) hotbarPlayers.computeIfAbsent(plugin, k -> new long[1])[0]++;
            String menu = pluginOf(player.getOpenInventory().getTopInventory().getHolder(false));
            if (menu != null) openMenus.computeIfAbsent(menu, k -> new long[1])[0]++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        // natural spawns, spawners, breeding, dropped items: the server's own doing
        if (event instanceof ItemSpawnEvent || event instanceof org.bukkit.event.entity.SpawnerSpawnEvent
                || event instanceof org.bukkit.event.entity.TrialSpawnerSpawnEvent) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof org.bukkit.entity.Projectile || entity instanceof org.bukkit.entity.ExperienceOrb
                || entity instanceof org.bukkit.entity.TNTPrimed || entity instanceof org.bukkit.entity.FallingBlock
                || entity instanceof org.bukkit.entity.AreaEffectCloud) {
            return;
        }
        long now = System.nanoTime();
        if (now - walkSecond > 1_000_000_000L) {
            walkSecond = now;
            walks = 0;
        }
        if (++walks > MAX_WALKS_PER_SECOND) return;
        if (event instanceof CreatureSpawnEvent creature && creature.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM
                && creature.getSpawnReason() != CreatureSpawnEvent.SpawnReason.DEFAULT) {
            return;
        }
        String plugin = caller();
        if (plugin == null) return;
        spawned.computeIfAbsent(plugin, k -> new HashMap<>()).merge(type(entity), 1, Integer::sum);
    }

    /** The plugins whose markers an entity carries (at the end of the measurement). */
    Set<String> markersOf(Entity entity) {
        Set<String> plugins = new HashSet<>(2);
        for (NamespacedKey key : keys(entity.getPersistentDataContainer())) {
            String plugin = namespaces.get(key.getNamespace());
            if (plugin != null) plugins.add(plugin);
        }
        return plugins;
    }

    static String type(Entity entity) {
        return entity.getType().name().toLowerCase(Locale.ROOT);
    }

    private void addPlugins(@Nullable ItemStack item, Set<String> plugins) {
        if (item == null || item.isEmpty()) return;
        for (NamespacedKey key : keys(item.getPersistentDataContainer())) {
            String plugin = namespaces.get(key.getNamespace());
            if (plugin != null) plugins.add(plugin);
        }
    }

    // Paper rebuilds each key and throws for one an NBT tool wrote that is no valid NamespacedKey
    private static Set<NamespacedKey> keys(io.papermc.paper.persistence.PersistentDataContainerView data) {
        try {
            return data.getKeys();
        } catch (IllegalArgumentException e) {
            return Set.of();
        }
    }

    private static @Nullable String pluginOf(@Nullable InventoryHolder holder) {
        return holder == null ? null : pluginOf(holder.getClass());
    }

    // the plugin a class belongs to (Bukkit and Paper plugins), null for the server's own classes
    private static @Nullable String pluginOf(Class<?> type) {
        if (type.getClassLoader() instanceof io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader loader) {
            try {
                return loader.getConfiguration().getName();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    // the plugin whose code is on the stack below the spawn event (our own measurement code left out)
    private static @Nullable String caller() {
        Class<?> found = WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type.getClassLoader() instanceof io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader
                        && !type.getName().startsWith("xyz.jupp.minecraft.perf."))
                .findFirst().orElse(null));
        return found == null ? null : pluginOf(found);
    }
}
