package xyz.jupp.minecraft.inventory;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.utils.Tasks;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class JewelerInventory {

    // goods of the jeweler in slot order (slot 10 - 16), price before tax
    public enum Offer {
        EMERALD("§aSmaragd", Material.EMERALD, 50),
        GOLD("§eGold", Material.GOLD_INGOT, 100),
        DIAMOND("§bDiamant", Material.DIAMOND, 250),
        NETHERITE("§8Netherite", Material.NETHERITE_INGOT, 1000),
        AMETHYST("§5Amethyst", Material.AMETHYST_SHARD, 200),
        RESIN("§6Harz", Material.RESIN_CLUMP, 300),
        LAPIS("§9Lapislazuli", Material.LAPIS_LAZULI, 50);

        private final String label;
        private final Material material;
        private final int price;

        Offer(String label, Material material, int price) {
            this.label = label;
            this.material = material;
            this.price = price;
        }

        // the first offer whose label starts the clicked item name
        public static @Nullable Offer byItemName(@NotNull String displayName) {
            for (Offer offer : values()) {
                if (displayName.startsWith(offer.label)) return offer;
            }
            return null;
        }

        public Material getMaterial() {
            return material;
        }

        public int getPrice() {
            return price;
        }
    }

    public static void openInventory(@NotNull Player player) {
        Tasks.sync(() -> {
            player.closeInventory();
            player.openInventory(createNewJewelerShopInventory());
            player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 2f, 2f);
        });
    }

    private static Inventory createNewJewelerShopInventory() {
        Inventory inventory = Menu.create(Menu.Type.JEWELER, 27, "§8Tresen des %s's".formatted(Main.getJewelerVillagerName()));

        String currentTax = String.valueOf(Math.toIntExact(Math.round(ConfigManager.getManager().getTradeTax() * 100)));
        String [] lores = {"§c+" + currentTax + "% Steuern"};

        ItemStack grayPane = createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, grayPane);
        }
        for (Offer offer : Offer.values()) {
            inventory.setItem(10 + offer.ordinal(), createItemStack("%s §8(%s§8)".formatted(offer.label, Main.getCurrencyName(offer.price)), offer.material, lores));
        }
        return inventory;
    }

}
