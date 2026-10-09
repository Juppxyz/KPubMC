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


    private static Inventory createSendInventory(Player player) {
        Inventory inventory = Menu.create(Menu.Type.MONEY, 54, "§aGeldbeutel §8» §fGeld verwalten");

        int invIndex = 0;
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.getUniqueId().equals(player.getUniqueId())) continue;
            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();
            playerHeadMeta.setOwningPlayer(onlinePlayer);
            playerHeadMeta.customName(Text.of("§2§o" + onlinePlayer.getName()));
            playerHead.setItemMeta(playerHeadMeta);
            inventory.setItem(invIndex, playerHead);
            invIndex++;
        }

        inventory.setItem(45, createItemStack("§c- 100", Material.LIGHT_GRAY_WOOL));
        inventory.setItem(46, createItemStack("§c- 10", Material.LIGHT_GRAY_WOOL));
        inventory.setItem(47, createItemStack("§c- 1", Material.GRAY_DYE));
        inventory.setItem(48, createItemStack("0", Material.DIAMOND));
        inventory.setItem(49, createItemStack("§a+ 1", Material.LIME_DYE));
        inventory.setItem(50, createItemStack("§a+ 10", Material.LIME_WOOL));
        inventory.setItem(51, createItemStack("§a+ 100", Material.LIME_WOOL));
        inventory.setItem(52, createItemStack("§aAbheben §8(§6-" + Math.round(Taxes.rate(TaxClass.STANDARD) * 100) + "%§8)", Material.NETHER_STAR));
        inventory.setItem(53, createItemStack(closeInventoryName, Material.BARRIER));
        return inventory;
    }

}
