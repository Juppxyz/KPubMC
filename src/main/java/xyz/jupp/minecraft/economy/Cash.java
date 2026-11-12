package xyz.jupp.minecraft.economy;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.SeasonReset;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Cash: bank notes of 10, 100 and 1.000 Schilling. A note is recognized by an invisible marker with its value, never by
 * its name, so a renamed piece of paper is worth nothing. Notes cannot be crafted with or traded to villagers
 * (CashGuardListener). The old cash (emeralds named "10 Schilling", recognized by the name) is only taken by Basil.
 * Every note carries the season it was printed in ({@link SeasonReset}): after a season reset the notes of the old
 * season, and the old emeralds, are worth nothing, money cannot be carried over as paper.
 * Main thread only (load() in onEnable).
 */
public final class Cash {

    private Cash() {}

    public static final int[] NOTES = {1_000, 100, 10};
    private static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "cash");
    // the season a note was printed in; notes from before this key existed count as season 0
    private static final NamespacedKey SEASON_KEY = new NamespacedKey(Main.getInstance(), "cash_season");
    private static final int LEGACY_VALUE = 10;
    private static volatile int season;

    /** Blocking, in onEnable after a marked season reset ran: the season notes are printed for and valid in. */
    public static void load() {
        season = SeasonReset.season();
    }

    /** A stack of notes of one value (10, 100 or 1.000). */
    public static ItemStack note(int value, int amount) {
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
        // only from the first reset on: notes of season 0 stay like the ones printed before the stamp, so they stack
        if (season > 0) meta.getPersistentDataContainer().set(SEASON_KEY, PersistentDataType.INTEGER, season);
        note.setItemMeta(meta);
        return note;
    }

    /** Value of one note of the stack, 0 if it is no note of this season; only the values Basil prints count. */
    public static int value(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return 0;
        Integer value = item.getPersistentDataContainer().get(KEY, PersistentDataType.INTEGER);
        if (value == null || printedIn(item) != season) return 0;
        for (int note : NOTES) {
            if (note == value) return value;
        }
        return 0;
    }

    /** A note printed before the last season reset: worth nothing now. */
    public static boolean isOldSeason(@Nullable ItemStack item) {
        return item != null && item.getType() == Material.PAPER
                && item.getPersistentDataContainer().has(KEY, PersistentDataType.INTEGER) && printedIn(item) != season;
    }

    /** Old notes (or the old emeralds of an earlier season) in the storage slots. */
    public static boolean hasOldSeason(@NotNull Player player) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isOldSeason(stack)) return true;
            if (season > 0 && stack != null && stack.getType() == Material.EMERALD && legacyName(stack)) return true;
        }
        return false;
    }

    private static int printedIn(ItemStack item) {
        Integer printed = item.getPersistentDataContainer().get(SEASON_KEY, PersistentDataType.INTEGER);
        return printed == null ? 0 : printed;
    }

    /** The old cash: an emerald with the name of 10 Schilling, only valid in the first season (season 0). */
    public static boolean isLegacy(@Nullable ItemStack item) {
        if (season > 0 || item == null || item.getType() != Material.EMERALD) return false;
        return legacyName(item);
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

    /** Takes all cash from the storage slots and returns its value; with legacy also the old emeralds. */
    public static long takeAll(@NotNull Player player, boolean legacy) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        long total = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) continue;
            int value = value(stack);
            if (value == 0 && legacy && isLegacy(stack)) value = LEGACY_VALUE;
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
        if (price <= 0 || total(player) < price) return false;
        long total = takeAll(player, false);
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

}
