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

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class JewelerInventory {

    // NOT IN USE

    public enum JewelerInventoryType {MAIN}

    public static void openInventory(@NotNull Player player, @NotNull JewelerInventoryType jewelerInventoryType) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                player.closeInventory();
                if (jewelerInventoryType.equals(JewelerInventoryType.MAIN)) {
                    player.openInventory(createNewJewelerShopInventory(player));
                    player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 2f, 2f);
                }
            });
        });
    }

    private static Inventory createNewJewelerShopInventory(Player player) {
        Inventory inventory = Bukkit.createInventory(player, 27, "§8Tresen des %s's".formatted(Main.getJewelerVillagerName()));

        String currentTax = String.valueOf(Math.toIntExact(Math.round(ConfigManager.getManager().getTradeTax() * 100)));
        String [] lores = {"§c+" + currentTax + "% Steuern"};

        for (int i = 0; i < 27; i++) {
            if (i == 10) {inventory.setItem(i, createItemStack("§aSmaragd §8(%s§8)".formatted(Main.getCurrencyName(50)), Material.EMERALD, lores));continue;}
            if (i == 11) {inventory.setItem(i, createItemStack("§eGold §8(%s§8)".formatted(Main.getCurrencyName(100)), Material.GOLD_INGOT, lores));continue;}
            if (i == 12) {inventory.setItem(i, createItemStack("§bDiamant §8(%s§8)".formatted(Main.getCurrencyName(250)), Material.DIAMOND, lores));continue;}
            if (i == 13) {inventory.setItem(i, createItemStack("§8Netherite §8(%s§8)".formatted(Main.getCurrencyName(1000)), Material.NETHERITE_INGOT, lores));continue;}
            if (i == 14) {inventory.setItem(i, createItemStack("§5Amethyst §8(%s§8)".formatted(Main.getCurrencyName(200)), Material.AMETHYST_SHARD, lores));continue;}
            if (i == 15) {inventory.setItem(i, createItemStack("§6Harz §8(%s§8)".formatted(Main.getCurrencyName(300)), Material.RESIN_CLUMP, lores));continue;}
            if (i == 16) {inventory.setItem(i, createItemStack("§9Lapislazuli §8(%s§8)".formatted(Main.getCurrencyName(50)), Material.LAPIS_LAZULI, lores));continue;}
            inventory.setItem(i, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        }


        return inventory;
    }

}
