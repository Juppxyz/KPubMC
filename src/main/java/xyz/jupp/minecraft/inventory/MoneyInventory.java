package xyz.jupp.minecraft.inventory;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.economy.TaxClass;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;


public class MoneyInventory {

    public final static String moneySendName = "§aGeld verwalten";
    public final static String closeInventoryName = "§6Bye";

    public enum MoneyInventoryTypes { MAIN, SEND }


    // wrapper
    public static void openInventory(@NotNull Player player, @NotNull MoneyInventoryTypes moneyInventoryType) {
        if (moneyInventoryType == MoneyInventoryTypes.SEND) {
            Tasks.sync(() -> open(player, createSendInventory(player)));
            return;
        }
        Tasks.async(() -> {
            final int money = PlayerRepository.getMoney(player);
            MainThread.run(() -> open(player, createMainInventory(money)));
        });
    }

    private static void open(Player player, Inventory inventory) {
        player.closeInventory();
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
    }


    private static Inventory createMainInventory(int money) {
        Inventory inventory = Menu.create(Menu.Type.MONEY, 9, "§aGeldbeutel §8» §fÜbersicht");
        for (int i = 0; i < 9; i++) {
            if (i == 0) {
                inventory.setItem(i, createItemStack("§fDein Guthaben§8: " + Main.getCurrencyName(money), Material.GOLD_INGOT));
            } else if ((i == 4) && (money > 0))  {
                inventory.setItem(i, createItemStack(moneySendName, Material.EMERALD));
            } else if (i == 8) {
                inventory.setItem(i, createItemStack(closeInventoryName, Material.BARRIER));
            } else {
                inventory.setItem(i, createItemStack("§8---", Material.GRAY_STAINED_GLASS_PANE));
            }
        }
        return inventory;
    }


    /** Receivers per page: the top five rows; the bottom row holds the buttons. */
    public static final int HEADS_PER_PAGE = 45;
    public static final int PAGE_SLOT = 53;
    public static final String nextPageName = "§fNächste Seite ▶";

    private static Inventory createSendInventory(Player player) {
        Inventory inventory = Menu.create(Menu.Type.MONEY, 54, "§aGeldbeutel §8» §fGeld verwalten");
        showReceivers(player, inventory, 0);

        inventory.setItem(45, createItemStack("§c- 100", Material.LIGHT_GRAY_WOOL));
        inventory.setItem(46, createItemStack("§c- 10", Material.LIGHT_GRAY_WOOL));
        inventory.setItem(47, createItemStack("§c- 1", Material.GRAY_DYE));
        inventory.setItem(48, createItemStack("0", Material.DIAMOND));
        inventory.setItem(49, createItemStack("§a+ 1", Material.LIME_DYE));
        inventory.setItem(50, createItemStack("§a+ 10", Material.LIME_WOOL));
        inventory.setItem(51, createItemStack("§a+ 100", Material.LIME_WOOL));
        inventory.setItem(52, createItemStack("§aAbheben §7(§6-" + Math.round(Taxes.rate(TaxClass.STANDARD) * 100) + "%§7)", Material.NETHER_STAR));
        return inventory;
    }

    // everyone else online, by name (a stable order while players come and go)
    private static List<Player> receivers(Player player) {
        List<Player> others = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(player.getUniqueId())) others.add(online);
        }
        others.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return others;
    }

    /**
     * Main thread: the heads of one page in the top rows (in place, the amount and the chosen receiver stay); with
     * more receivers than fit, the last slot turns the page (closing works with Escape).
     */
    public static void showReceivers(@NotNull Player player, @NotNull Inventory inventory, int page) {
        List<Player> others = receivers(player);
        int pages = Math.max(1, (others.size() + HEADS_PER_PAGE - 1) / HEADS_PER_PAGE);
        int shown = Math.floorMod(page, pages);
        Menu menu = Menu.of(inventory);
        if (menu != null) menu.setPage(shown);
        for (int slot = 0; slot < HEADS_PER_PAGE; slot++) {
            int index = shown * HEADS_PER_PAGE + slot;
            if (index >= others.size()) {
                inventory.setItem(slot, null);
                continue;
            }
            Player other = others.get(index);
            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();
            playerHeadMeta.setOwningPlayer(other);
            playerHeadMeta.customName(Text.of("§2§o" + other.getName()));
            playerHead.setItemMeta(playerHeadMeta);
            inventory.setItem(slot, playerHead);
        }
        // the page button while more receivers than fit are online, the close button otherwise (also when that changes)
        inventory.setItem(PAGE_SLOT, pages > 1
                ? createItemStack(nextPageName, Material.ARROW, new String[]{"§7Seite " + (shown + 1) + " von " + pages})
                : createItemStack(closeInventoryName, Material.BARRIER));
    }

}
