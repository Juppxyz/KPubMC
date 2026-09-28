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
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Cash: bank notes of 10, 100 and 1.000 Schilling. A note is recognized by an invisible marker with its value, never by
 * its name, so a renamed piece of paper is worth nothing. Notes cannot be crafted with or traded to villagers
 * (CashGuardListener). The old cash (emeralds named "10 Schilling", recognized by the name) is only taken by Basil.
 * Main thread only.
 */
public final class Cash {

    private Cash() {}

    public static final int[] NOTES = {1_000, 100, 10};
    private static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "cash");
    private static final int LEGACY_VALUE = 10;

    /** A stack of notes of one value (10, 100 or 1.000). */
    public static ItemStack note(int value, int amount) {
        ItemStack note = new ItemStack(Material.PAPER, amount);
        ItemMeta meta = note.getItemMeta();
        meta.customName(Text.of(switch (value) {
            case 1_000 -> "§61.000 Schilling";
            case 100 -> "§b100 Schilling";
            default -> "§a10 Schilling";
        }));
        meta.lore(Text.lore(List.of("§5Bargeld", "§8Einzahlen bei Basil, Morpheus nimmt nur das.")));
        if (value >= 1_000) meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.INTEGER, value);
        note.setItemMeta(meta);
        return note;
    }

    /** Value of one note of the stack, 0 if it is no (new) note. */
    public static int value(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return 0;
        Integer value = item.getPersistentDataContainer().get(KEY, PersistentDataType.INTEGER);
        return value == null || value <= 0 ? 0 : value;
    }

    /** The old cash: an emerald with the name of 10 Schilling. */
    public static boolean isLegacy(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.EMERALD) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && Main.getCurrencyName(LEGACY_VALUE).equals(Text.legacyOrNull(meta.customName()));
    }

    public static boolean isAnyCash(@Nullable ItemStack item) {
        return value(item) > 0 || isLegacy(item);
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
