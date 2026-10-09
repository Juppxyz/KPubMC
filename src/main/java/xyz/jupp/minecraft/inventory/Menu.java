package xyz.jupp.minecraft.inventory;

import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.utils.Text;

/**
 * Holder of every plugin GUI. The click listeners recognize their menu by this holder instead of the title.
 */
public final class Menu implements InventoryHolder {

    public enum Type { MONEY }

    private final Type type;
    private final int page;
    private Inventory inventory;

    private Menu(@NotNull Type type, int page) {
        this.type = type;
        this.page = page;
    }

    // chest-type menu, the title looks exactly like the former String title
    public static Inventory create(@NotNull Type type, int size, @NotNull String title) {
        return create(type, 0, size, title);
    }

    public static Inventory create(@NotNull Type type, int page, int size, @NotNull String title) {
        Menu menu = new Menu(type, page);
        menu.inventory = Bukkit.createInventory(menu, size, Text.section(title));
        return menu.inventory;
    }

    // block-type menu (DISPENSER), whose String title was converted like an item name
    public static Inventory create(@NotNull Type type, @NotNull InventoryType inventoryType, @NotNull String title) {
        Menu menu = new Menu(type, 0);
        menu.inventory = Bukkit.createInventory(menu, inventoryType, Text.of(title));
        return menu.inventory;
    }

    // the menu of a top inventory, null for every other inventory
    public static @Nullable Menu of(@NotNull Inventory inventory) {
        return inventory.getHolder(false) instanceof Menu menu ? menu : null;
    }

    public static boolean is(@NotNull Inventory inventory, @NotNull Type type) {
        Menu menu = of(inventory);
        return menu != null && menu.type == type;
    }

    public Type getType() {
        return type;
    }

    public int getPage() {
        return page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

}
