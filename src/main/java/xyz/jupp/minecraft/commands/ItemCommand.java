package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.items.BedrockBreakerPickaxe;
import xyz.jupp.minecraft.items.CustomItemsInterface;
import xyz.jupp.minecraft.items.Flamethrower;
import xyz.jupp.minecraft.items.ForgedPapers;
import xyz.jupp.minecraft.items.GrapplingHook;
import xyz.jupp.minecraft.items.KeepInventoryItem;
import xyz.jupp.minecraft.items.LightningSword;
import xyz.jupp.minecraft.items.PoisonBow;
import xyz.jupp.minecraft.items.TrackerCompass;
import xyz.jupp.minecraft.utils.BlackMarketHandler;
import xyz.jupp.minecraft.utils.PermissionsUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// admins only: /customItem morpheus gives every black market item, /customItem <name> a single one
public class ItemCommand implements CommandExecutor, TabCompleter {

    private static final String ALL_MORPHEUS = "morpheus";

    private static final Map<String, Supplier<ItemStack>> ITEMS = new LinkedHashMap<>();

    static {
        ITEMS.put("sword", () -> new LightningSword().getSword());
        ITEMS.put("enterhaken", () -> new GrapplingHook().getItemStack());
        ITEMS.put("flammenwerfer", () -> new Flamethrower().getItemStack());
        ITEMS.put("giftbogen", () -> new PoisonBow().getItemStack());
        ITEMS.put("bedrockbreaker", () -> new BedrockBreakerPickaxe().getItemStack());
        ITEMS.put("keepinventory", () -> new KeepInventoryItem().getItemStack());
        ITEMS.put("kompass", () -> new TrackerCompass().getItemStack());
        ITEMS.put("papiere", () -> new ForgedPapers().getItemStack());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (!(commandSender instanceof Player player)) {
            return false;
        }
        if (!PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return false;
        }
        if (args.length == 0) {
            player.sendMessage(Main.getChatPrefix() + "Mach das: /customItem <" + ALL_MORPHEUS + "|" + String.join("|", ITEMS.keySet()) + ">");
            return false;
        }

        String itemName = args[0].toLowerCase();

        if (itemName.equals(ALL_MORPHEUS)) {
            List<CustomItemsInterface> items = BlackMarketHandler.items();
            for (CustomItemsInterface item : items) give(player, item.getItemStack());
            player.sendMessage(Main.getChatPrefix() + "Du hast alle " + items.size() + " Morpheus-Items erhalten.");
            return true;
        }

        Supplier<ItemStack> item = ITEMS.get(itemName);
        if (item == null) {
            player.sendMessage(Main.getChatPrefix() + "§cUnbekanntes Item: " + itemName);
            return true;
        }
        give(player, item.get());
        player.sendMessage(Main.getChatPrefix() + "Du hast §f" + itemName + " §7erhalten.");
        return true;
    }

    // what does not fit drops at the player's feet
    private static void give(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), left);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1 || !(sender instanceof Player player) || !PermissionsUtil.isPlayerAdmin(player)) return List.of();
        String typed = args[0].toLowerCase();
        List<String> names = new ArrayList<>();
        if (ALL_MORPHEUS.startsWith(typed)) names.add(ALL_MORPHEUS);
        for (String name : ITEMS.keySet()) {
            if (name.startsWith(typed)) names.add(name);
        }
        return names;
    }
}
