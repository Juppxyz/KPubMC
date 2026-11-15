package xyz.jupp.minecraft.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Cash: bank notes of 10, 100 and 1,000 printed by Basil (the banker). A note is a PAPER item with an invisible marker
 * (value and season); its name means nothing, a renamed paper is worth 0. Only read here: notes are taken and handed out
 * by {@link CasinoBank}, never build or copy note items yourself.
 * <p>
 * Rules for your plugin:
 * <ul>
 *     <li>Never keep notes yourself (in a chest, a GUI slot, a file): take a bet with {@link CasinoBank#takeBet} and pay
 *     with {@link CasinoBank#payWin}; a stored note can become worthless at a season reset.</li>
 *     <li>Never accept cash as goods or material (crafting, trading, as a block/item price): check {@link #looksLikeCash}.</li>
 *     <li>Only the 36 storage slots count (hotbar and main inventory), not the off-hand, armor or the cursor.</li>
 * </ul>
 * Main thread only.
 */
public interface CashNotes {

    /** The values Basil prints, largest first: 1000, 100, 10. Every amount of cash is a multiple of 10. */
    @NotNull List<Integer> noteValues();

    /** Value of ONE note of the stack (multiply by the amount yourself); 0 if it is no valid note of this season. */
    int valueOf(@Nullable ItemStack item);

    /** A note from an older season: worth nothing now (good for a message like "your notes are from last season"). */
    boolean isOldNote(@Nullable ItemStack item);

    /** Looks like cash of any season, valid or not: never treat such an item as goods or material. */
    boolean looksLikeCash(@Nullable ItemStack item);

    /** The value of all valid notes in the player's storage slots. */
    long cashOf(@NotNull Player player);

}
