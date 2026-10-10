package xyz.jupp.minecraft.listener;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.util.BoundingBox;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.config.DntLootRules;
import xyz.jupp.minecraft.utils.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

// Dungeons and Taverns fills its chests generously; valuables there would undercut the shop and Hondo.
// The loot is generated when a chest is opened (or broken) for the first time, so already generated
// but unopened chests are limited as well.
public class DntLootListener implements Listener {

    // each limited loot table is logged once per restart, to see in the console that the detection works
    private static final Set<NamespacedKey> LOGGED_TABLES = new HashSet<>();

    @EventHandler(ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        DntLootRules rules = ConfigManager.getManager().getDntLootRules();
        if (!rules.enabled() || event.isPlugin() || !isDnt(event, rules.namespaces())) return;

        NamespacedKey table = event.getLootTable().getKey();
        if (LOGGED_TABLES.add(table)) Logger.console("limiting DnT loot table " + table);

        Map<Material, Integer> left = new HashMap<>(rules.maxPerChest());
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack item : event.getLoot()) {
            if (item == null || item.isEmpty()) continue;
            if (isEnchanted(item) && ThreadLocalRandom.current().nextDouble() >= rules.enchantedKeepChance()) continue;
            Integer cap = left.get(item.getType());
            if (cap != null) {
                int amount = Math.min(item.getAmount(), cap);
                left.put(item.getType(), cap - amount);
                if (amount == 0) continue;
                item.setAmount(amount);
            }
            kept.add(item);
        }
        event.setLoot(kept);
    }

    // DnT loot tables, or any chest inside a DnT structure (in case one of its chests uses a vanilla loot table)
    private static boolean isDnt(LootGenerateEvent event, Set<String> namespaces) {
        if (namespaces.contains(event.getLootTable().getKey().getNamespace())) return true;

        Location location = event.getLootContext().getLocation();
        if (location.getWorld() == null) return false;
        Registry<Structure> structures = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
        for (GeneratedStructure generated : location.getChunk().getStructures()) {
            NamespacedKey key = structures.getKey(generated.getStructure());
            if (key == null || !namespaces.contains(key.getNamespace())) continue;
            // the pieces, not the whole structure box: jigsaw structures span far more than their buildings
            for (StructurePiece piece : generated.getPieces()) {
                if (contains(piece.getBoundingBox(), location)) return true;
            }
        }
        return false;
    }

    // inclusive on both ends, whether the box max is the last block or one past it does not matter here
    private static boolean contains(BoundingBox box, Location location) {
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        return box.getMinX() <= x && x <= box.getMaxX()
                && box.getMinY() <= y && y <= box.getMaxY()
                && box.getMinZ() <= z && z <= box.getMaxZ();
    }

    private static boolean isEnchanted(ItemStack item) {
        if (!item.getEnchantments().isEmpty()) return true;
        return item.getItemMeta() instanceof EnchantmentStorageMeta meta && meta.hasStoredEnchants();
    }
}
