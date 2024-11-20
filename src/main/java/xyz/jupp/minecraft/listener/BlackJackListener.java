package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.config.ShopItem;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.inventory.BlackJackInventory;
import xyz.jupp.minecraft.inventory.ShopInventory;
import xyz.jupp.minecraft.utils.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlackJackListener implements Listener {

    private final HashMap<UUID, BlackJackInventory> activeGames = new HashMap<>();


    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getType().equals(EntityType.VILLAGER)) {
            Villager villager = (Villager) event.getEntity();
            if (villager.isCustomNameVisible() && villager.getCustomName().equals("§a§lBlackJack Joe")) event.setCancelled(true);
        }
    }


    @EventHandler
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();

        // Check if the right-clicked entity is your special villager
        if (entity.getType().equals(EntityType.VILLAGER)) {
            Villager villager = (Villager) entity;


            // Use a custom name to identify the special villager
            if (entity.isCustomNameVisible() && entity.getCustomName().equals("§a§lBlackJack Joe")) {
                event.setCancelled(true); // Cancel the default villager interaction

                // Open the special inventory
                BlackJackInventory blackJackInventory = new BlackJackInventory();
                activeGames.put(player.getUniqueId(), blackJackInventory);
                player.openInventory(blackJackInventory.getInventory());
            }
        }
    }


    @NotNull
    private static ItemStack createNewItem(Material material, String name) {
        ItemStack itemStack = new ItemStack(material);
        return itemStack;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        Inventory inventory = event.getClickedInventory();

        // Ensure the clicked inventory is part of an active blackjack game
        if (inventory != null && activeGames.containsKey(player.getUniqueId())) {
            event.setCancelled(true); // Prevent default behavior

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getItemMeta() == null) return;

            // Get the active game for the player
            BlackJackInventory blackJackInventory = activeGames.get(player.getUniqueId());

            // check for bet selection
            if (clickedItem.getType() == Material.GOLD_INGOT) {
                String displayName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
                if (displayName.startsWith("Einsatz")) {
                    int betAmount = Integer.parseInt(displayName.split(" ")[1]);

                    // update inventory after bet selection
                    blackJackInventory.updateAfterBetSelection();
                    player.openInventory(blackJackInventory.getInventory());

                }
            }
        }
    }
}
