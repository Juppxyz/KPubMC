package xyz.jupp.minecraft.inventory;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.config.ShopItem;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class ShopInventory {

    public static void openInventory(@NotNull Player player) {
        Tasks.sync(() -> {
            player.closeInventory();
            player.openInventory(createNewMainShopInventory());
            player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 2f, 2f);
        });
    }


    private static Inventory createNewMainShopInventory() {
        Inventory inventory = Menu.create(Menu.Type.SHOP, 36, Main.getShopVillagerName());
        List<ShopItem> shopItems = ConfigManager.getShopItems();
        float tradeTax = ConfigManager.getManager().getTradeTax();

        ItemStack grayPane = createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 36; i++) {
            if (i < 10 || i == 17 || i == 18 || i >= 26) inventory.setItem(i, grayPane);
        }

        int allPrices = 0;
        int tmpInvIndex = 11;
        for (ShopItem shopItem : shopItems) {
            if (tmpInvIndex == 16) tmpInvIndex = 20;
            if (tmpInvIndex > 24) break;
            allPrices += shopItem.price();

            int price = shopItem.price();
            if (tradeTax != 0.0) {
                price = Math.round(price + (tradeTax*price));
            }
            inventory.setItem(tmpInvIndex, createNewShopItem(shopItem, price));
            tmpInvIndex++;
        }

        int price = (int) Math.round((allPrices / shopItems.size()) * 0.80);
        inventory.setItem(31, createItemStack("§5§oZufall", Material.EXPERIENCE_BOTTLE, new String[]{"§fPreis: " + Main.getCurrencyName(price)}));
        inventory.setItem(27, createItemStack("§fSteuersatz: §a" + Math.round(tradeTax*100) + "%", Material.BOOK));

        return inventory;
    }


    private static ItemStack createNewShopItem(@NotNull ShopItem shopItem, int price) {
        Material material = Material.matchMaterial(shopItem.material());
        // unknown material in the config: a barrier without a name
        if (material == null) return new ItemStack(Material.BARRIER);

        ItemStack itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemStack.setAmount(shopItem.amount());
        itemMeta.customName(Text.of(shopItem.name()));

        List<String> lores = new ArrayList<>(3);
        lores.add("§fPreis: " + Main.getCurrencyName(price));
        if (shopItem.sell()) lores.add("§fVerkaufen: " + Main.getCurrencyName(price/2));
        if (!shopItem.description().isEmpty()) {
            lores.add(shopItem.description());
        }
        itemMeta.lore(Text.lore(lores));

        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

}
