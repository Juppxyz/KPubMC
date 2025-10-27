package xyz.jupp.minecraft.inventory;

import org.bukkit.Bukkit;
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

import java.util.ArrayList;
import java.util.List;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class ShopInventory {

    public enum ShopInventoryTypes { MAIN }

    public static void openInventory(@NotNull Player player, @NotNull ShopInventoryTypes shopInventoryTypes) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () ->{
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                player.closeInventory();
                if (shopInventoryTypes.equals(ShopInventoryTypes.MAIN)) {
                    player.openInventory(createNewMainShopInventory(player));
                    player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 2f, 2f);
                }
            });
        });
    }


    private static Inventory createNewMainShopInventory(Player player) {
        boolean isReducedPrice = false;
        //PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
        //if (playerCacheObject.getTeamID() != null) {
        //    TeamCollection teamCollection = new TeamCollection(playerCacheObject.getTeamID());
        //    TeamBlockCacheObject teamBlockCacheObject = TeamBlockCache.getTeamBlock(teamCollection.getTeamID());
        //    isReducedPrice = (teamCollection.getTeamPoints() > 49) && teamBlockCacheObject.isActive();
        //}

        Inventory inventory = Bukkit.createInventory(player, 36, isReducedPrice ? "§aHändler §8(§aRabatte!§8)" : "§aHändler");
        List<ShopItem> shopItems = ConfigManager.getShopItems();
        int allPrices = 0;
        for (int i = 0; i < 36; i++) {
            if ((i == 17) || (i == 26) || (i == 18) || (i==0)) inventory.setItem(i, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
            if (i >26 || (i>0 && i <10)) inventory.setItem(i, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        }



        int tmpInvIndex = 11;
        for (int i = 0; i < shopItems.size(); i++) {
            if (tmpInvIndex == 16) tmpInvIndex = 20;
            if (tmpInvIndex > 24) break;
            allPrices += shopItems.get(i).getPrice();
            ShopItem shopItem = shopItems.get(i);
            int price = shopItem.getPrice();

            float tradeTax = ConfigManager.getManager().getTradeTax();
            if (tradeTax != 0.0) {
                price = Math.round(price + (tradeTax*price));
            }

            if (isReducedPrice) {
                float discount = price - (shopItem.getPrice() * 0.2f);
                price = Math.round(discount);
            }
            inventory.setItem(tmpInvIndex, createNewShopItem(
                    shopItem.getName(),
                    shopItem.getMaterial(),
                    price,
                    shopItem.isSell(),
                    shopItem.getAmount(),
                    shopItem.getDescription(), isReducedPrice)
            );
            tmpInvIndex++;
        }

        ItemStack itemStack = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta itemMeta = itemStack.getItemMeta();
        List<String> lore = new ArrayList<>(1);
        int price = (int) Math.round((allPrices / shopItems.size()) * 0.80);
        lore.add("§fPreis: " + Main.getCurrencyName(price));
        itemMeta.setLore(lore);
        itemMeta.setDisplayName("§5§oZufall");
        itemStack.setItemMeta(itemMeta);
        inventory.setItem(31, itemStack);

        ItemStack itemStackTaxInfo = new ItemStack(Material.BOOK);
        ItemMeta itemMetaTaxInfo = itemStackTaxInfo.getItemMeta();
        itemMetaTaxInfo.setDisplayName("§fSteuersatz: §a" + Math.round(ConfigManager.getManager().getTradeTax()*100) + "%");
        itemStackTaxInfo.setItemMeta(itemMetaTaxInfo);
        inventory.setItem(27, itemStackTaxInfo);

        return inventory;
    }


    private static ItemStack createNewShopItem(@NotNull String name, @NotNull String materialName, int price, boolean sell, int amount, String description, boolean isReduced) {
        ItemStack itemStack = null;
        Material material = Material.matchMaterial(materialName);
        if (material == null){
            itemStack = new ItemStack(Material.BARRIER);
            itemStack.getItemMeta().setDisplayName("§4§lFehler!");
            itemStack.setItemMeta(itemStack.getItemMeta());
            return itemStack;
        }
        itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemStack.setAmount(amount);
        itemMeta.setDisplayName(name);

        List<String> lores = new ArrayList<>(3);
        lores.add(String.format("§fPreis: %s", Main.getCurrencyName(price)));
        if (sell) lores.add(String.format("§fVerkaufen: %s", (isReduced ? Main.getCurrencyName((int) Math.round(((price/2)*0.2) + (price/2))) : Main.getCurrencyName(price/2))));
        if (!description.equals("")) {
            lores.add(description);
        }
        itemMeta.setLore(lores);

        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

}
