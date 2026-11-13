package xyz.jupp.minecraft.inventory;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.utils.Tasks;


import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;


public class MoneyInventory {

    // the button of the overview; the menu behind it only withdraws cash (no transfers between accounts since 2026-11-13)
    public final static String moneySendName = "§aBargeld abheben";
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


    /** The withdrawal menu: an info row, then the amount buttons, the amount, Abheben and close. */
    public static final int AMOUNT_SLOT = 12;
    public static final int ACTION_SLOT = 16;

    private static Inventory createSendInventory(Player player) {
        Inventory inventory = Menu.create(Menu.Type.MONEY, 18, "§aGeldbeutel §8» §fBargeld abheben");
        for (int slot = 0; slot < 9; slot++) inventory.setItem(slot, createItemStack("§8---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(4, createItemStack("§fBargeld", Material.PAPER, new String[]{
                "§7Mit Bargeld bezahlst du andere Spieler:",
                "§7Scheine einfach weitergeben.",
                "§7Einzahlen geht bei Basil."}));
        inventory.setItem(9, createItemStack("§c- 100", Material.LIGHT_GRAY_WOOL));
        inventory.setItem(10, createItemStack("§c- 10", Material.LIGHT_GRAY_WOOL));
        inventory.setItem(11, createItemStack("§c- 1", Material.GRAY_DYE));
        inventory.setItem(AMOUNT_SLOT, createItemStack("0", Material.DIAMOND));
        inventory.setItem(13, createItemStack("§a+ 1", Material.LIME_DYE));
        inventory.setItem(14, createItemStack("§a+ 10", Material.LIME_WOOL));
        inventory.setItem(15, createItemStack("§a+ 100", Material.LIME_WOOL));
        inventory.setItem(ACTION_SLOT, createItemStack("§aAbheben §7(§6-" + Math.round(Taxes.cashWithdrawRate() * 100) + "%§7)", Material.NETHER_STAR));
        inventory.setItem(17, createItemStack(closeInventoryName, Material.BARRIER));
        return inventory;
    }

}
