package xyz.jupp.minecraft.economy;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Basil's vault: 27 slots per player, stored in bank_vaults.
 * <p>
 * Against duplicated items: after the first load the contents in memory are the truth (main thread), the database only
 * follows. Loads and saves run one after another on one thread, so a load never reads an older state than a save
 * before it. On closing, the player data is saved as well: a crash right after cannot give an item back to both.
 */
public final class Vault implements InventoryHolder {

    public static final int SIZE = 27;

    // contents per player after the first load (main thread only)
    private static final Map<UUID, ItemStack[]> contents = new ConcurrentHashMap<>();
    private static final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "KPubMC-Vault");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean shuttingDown;

    private final UUID owner;
    private final Inventory inventory;

    private Vault(UUID owner, ItemStack[] items) {
        this.owner = owner;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.section("§5§lSchließfach §8» §7" + Main.getFinanceVillagerFredName()));
        inventory.setContents(copy(items));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Main thread, the fee is paid: loads the vault (the first time) and opens it. */
    public static void open(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (contents.containsKey(uuid)) {
            show(player);
            return;
        }
        io.execute(() -> {
            byte[] bytes;
            try {
                bytes = Bank.loadVault(uuid);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Vault of {} could not be loaded: {}", uuid, e.toString());
                MainThread.run(() -> player.sendMessage(Bank.PREFIX + "Das Schließfach klemmt gerade, versuch es gleich nochmal."));
                return;
            }
            MainThread.run(() -> {
                // decoded on the main thread; an earlier load that finished first wins
                contents.computeIfAbsent(uuid, key -> decode(bytes));
                if (player.isOnline()) show(player);
            });
        });
    }

    private static void show(Player player) {
        // a vault that is still open stores its contents first (close event), only then the truth is read
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Vault) player.closeInventory();
        Vault vault = new Vault(player.getUniqueId(), contents.get(player.getUniqueId()));
        player.openInventory(vault.inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 0.8f, 1.2f);
    }

    /** Main thread: the vault was closed (also on quit and death), its contents become the truth and are saved. */
    public void closed(@NotNull Player player) {
        if (shuttingDown) return;
        store(player);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 0.8f, 1.2f);
    }

    private void store(Player player) {
        ItemStack[] items = copy(inventory.getContents());
        contents.put(owner, items);
        byte[] bytes = ItemStack.serializeItemsAsBytes(items);
        player.saveData();
        io.execute(() -> save(owner, bytes));
    }

    private static void save(UUID owner, byte[] bytes) {
        try {
            Bank.saveVault(owner, bytes);
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().error("Vault of {} could not be saved", owner, e);
        }
    }

    /** onDisable, before the database closes: stores the open vaults and waits for the pending saves. */
    public static void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Vault vault) vault.store(player);
        }
        shuttingDown = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Vault) player.closeInventory();
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                Main.getInstance().getSLF4JLogger().error("Vault saves did not finish in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ItemStack[] decode(byte[] bytes) {
        ItemStack[] items = new ItemStack[SIZE];
        if (bytes == null) return items;
        ItemStack[] stored = ItemStack.deserializeItemsFromBytes(bytes);
        for (int i = 0; i < Math.min(SIZE, stored.length); i++) items[i] = stored[i] == null || stored[i].isEmpty() ? null : stored[i];
        return items;
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[SIZE];
        for (int i = 0; i < Math.min(SIZE, items.length); i++) copy[i] = items[i] == null ? null : items[i].clone();
        return copy;
    }

}
