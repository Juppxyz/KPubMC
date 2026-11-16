package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.SeasonReset;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cash: bank notes of 10, 100 and 1.000 Schilling. A note is recognized by an invisible marker with its value, never by
 * its name, so a renamed piece of paper is worth nothing. Notes cannot be crafted with or traded to villagers
 * (CashGuardListener). Every note carries the season it was printed in ({@link SeasonReset}), also season 0: after a
 * season reset the notes of the old season are worth nothing, money cannot be carried over as paper, and a note without
 * the stamp (printed by another plugin, e.g. a casino in its legacy mode) is worth nothing at all. The old cash of the
 * first server (emeralds named "10 Schilling", recognized by the name only, so easy to forge) is no longer taken.
 * Main thread only (load() in onEnable).
 */
public final class Cash {

    private Cash() {}

    public static final int[] NOTES = {1_000, 100, 10};
    private static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "cash");
    // the season a note was printed in; a note without it is worth nothing
    private static final NamespacedKey SEASON_KEY = new NamespacedKey(Main.getInstance(), "cash_season");
    private static final int LEGACY_VALUE = 10;
    private static volatile int season;
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    // foreign callers already reported in the log (once each)
    private static final Set<String> refused = ConcurrentHashMap.newKeySet();

    /** Blocking, in onEnable after a marked season reset ran: the season notes are printed for and valid in. */
    public static void load() {
        season = SeasonReset.season();
    }

    /** The season notes are printed for and valid in. */
    public static int season() {
        return season;
    }

    /** A stack of notes of one value (10, 100 or 1.000); for KPubMC's own code only. */
    public static ItemStack note(int value, int amount) {
        ownCallerOnly();
        ItemStack note = new ItemStack(Material.PAPER, amount);
        ItemMeta meta = note.getItemMeta();
        meta.customName(Text.of(switch (value) {
            case 1_000 -> "§61.000 Schilling";
            case 100 -> "§b100 Schilling";
            default -> "§a10 Schilling";
        }));
        meta.lore(Text.lore(List.of("§5Bargeld", "§8Einzahlen / Handeln bei Basil")));
        if (value >= 1_000) meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.INTEGER, value);
        // always, also in season 0: only a stamped note counts
        meta.getPersistentDataContainer().set(SEASON_KEY, PersistentDataType.INTEGER, season);
        note.setItemMeta(meta);
        return note;
    }

    /** Value of one note of the stack, 0 if it is no note stamped with this season; only the values Basil prints count. */
    public static int value(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return 0;
        Integer value = item.getPersistentDataContainer().get(KEY, PersistentDataType.INTEGER);
        Integer printed = printedIn(item);
        if (value == null || printed == null || printed != season) return 0;
        for (int note : NOTES) {
            if (note == value) return value;
        }
        return 0;
    }

    /** A marked note that is worth nothing now: printed before the last season reset, or without a season stamp. */
    public static boolean isOldSeason(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.getPersistentDataContainer().has(KEY, PersistentDataType.INTEGER)) return false;
        Integer printed = printedIn(item);
        return printed == null || printed != season;
    }

    /** Worthless notes or old emerald cash in the storage slots (for the message why nothing was taken). */
    public static boolean hasOldSeason(@NotNull Player player) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isOldSeason(stack)) return true;
            if (stack != null && stack.getType() == Material.EMERALD && legacyName(stack)) return true;
        }
        return false;
    }

    // the season stamped on the note, null without a stamp
    private static @Nullable Integer printedIn(ItemStack item) {
        return item.getPersistentDataContainer().get(SEASON_KEY, PersistentDataType.INTEGER);
    }

    private static boolean legacyName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && Main.getCurrencyName(LEGACY_VALUE).equals(Text.legacyOrNull(meta.customName()));
    }

    /**
     * Looks like cash, of any season (a printed note, an old emerald): never crafted with, traded to villagers or sold
     * in a chest shop, also once it is worth nothing after a season reset (an old emerald would count at a villager).
     */
    public static boolean isAnyCash(@Nullable ItemStack item) {
        if (item == null) return false;
        if (item.getType() == Material.PAPER) return item.getPersistentDataContainer().has(KEY, PersistentDataType.INTEGER);
        return item.getType() == Material.EMERALD && legacyName(item);
    }

    /** Cash in the storage slots (without the old emeralds). */
    public static long total(@NotNull Player player) {
        long total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) total += (long) value(stack) * (stack == null ? 0 : stack.getAmount());
        return total;
    }

    /** Takes all cash from the storage slots and returns its value. */
    public static long takeAll(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        long total = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) continue;
            int value = value(stack);
            if (value == 0) continue;
            total += (long) value * stack.getAmount();
            contents[i] = null;
        }
        if (total > 0) inventory.setStorageContents(contents);
        return total;
    }

    /**
     * Pays with notes from the storage slots: all notes are taken and the change is handed back in as few notes as
     * possible. False (and nothing taken) if the cash is not enough.
     */
    public static boolean pay(@NotNull Player player, int price) {
        // before the notes are taken: the change is printed by note()
        ownCallerOnly();
        if (price <= 0 || total(player) < price) return false;
        long total = takeAll(player);
        give(player, total - price);
        return true;
    }

    /** Hands out the amount in as few notes as possible (a rest below 10 is lost); what does not fit is dropped. */
    public static void give(@NotNull Player player, long amount) {
        for (ItemStack stack : notes(amount)) {
            player.getInventory().addItem(stack).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        }
    }

    public static List<ItemStack> notes(long amount) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int value : NOTES) {
            long count = amount / value;
            amount -= count * value;
            while (count > 0) {
                int stack = (int) Math.min(64, count);
                stacks.add(note(value, stack));
                count -= stack;
            }
        }
        return stacks;
    }

    /*
     * Notes are printed only for KPubMC's own code: a call from another plugin, also by reflection, is refused before
     * anything moves. Other plugins go through the API (xyz.jupp.minecraft.api.KPubEconomy), which pays casino wins out
     * of the casino till instead of printing them. A guard against shortcuts, not a wall: code in the same server could
     * still write the marker itself.
     */
    private static void ownCallerOnly() {
        /*
         * The first frame outside this class and the JDK decides. Reflection frames are skipped by the walker, so a
         * reflective call shows its caller; JDK frames (a stream or Optional running a callback) are skipped here, the
         * caller is further down. No such frame at all (a method reference run on a JDK thread) is refused too.
         */
        Class<?> caller = WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != Cash.class && !isJdk(type)).findFirst().orElse(null));
        if (caller != null && caller.getClassLoader() == Cash.class.getClassLoader()) return;
        String name = caller == null ? "no caller (JDK thread)" : caller.getName();
        if (refused.add(name)) {
            Main.getInstance().getSLF4JLogger().warn("{} tried to print cash notes directly ({}), refused: other plugins use the KPubMC API",
                    pluginOf(caller), name);
        }
        throw new IllegalStateException("Only KPubMC prints cash notes; use the API xyz.jupp.minecraft.api.KPubEconomy (casino: takeBet/payWin)");
    }

    // the JDK's own classes (boot and platform loader)
    private static boolean isJdk(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader();
    }

    private static String pluginOf(@Nullable Class<?> type) {
        if (type == null) return "Foreign code";
        try {
            return "Plugin " + JavaPlugin.getProvidingPlugin(type).getName();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Foreign code";
        }
    }

}
