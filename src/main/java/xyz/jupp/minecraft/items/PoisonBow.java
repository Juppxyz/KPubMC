package xyz.jupp.minecraft.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import xyz.jupp.minecraft.Main;

import java.util.List;

public class PoisonBow extends BlackMarketItem {

    public static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "poison_bow");

    private static final List<String> BASE_LORE = List.of("§2Schießt vergiftete Pfeile ab!", " ");

    public PoisonBow() {
        super("§2§lCursedBow", Material.BOW, 12500, KEY, BASE_LORE);
    }

    @Override
    public ItemStack getItemStack() {
        ItemStack item = super.getItemStack();
        item.addEnchantment(Enchantment.MENDING, 1);
        return item;
    }

}
