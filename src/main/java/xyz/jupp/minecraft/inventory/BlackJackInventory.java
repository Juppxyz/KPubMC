package xyz.jupp.minecraft.inventory;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class BlackJackInventory {

    private final Inventory inventory;

    public BlackJackInventory() {
        // Create an inventory with a size of 54 (6 rows of 9) and a custom title
        this.inventory = Bukkit.createInventory(null, 54, "Blackjack: Choose Your Bet");

        // Initially show only bet selection buttons
        initializeBetSelection();
    }

    // Initialize the inventory with only bet buttons and placeholders
    private void initializeBetSelection() {
        // Add starting bet buttons
        int[] betSlots = {29, 30, 31, 32, 33}; // Centered slots in the 4th line
        int[] betAmounts = {10, 20, 50, 100, 200}; // Corresponding bet amounts
        for (int i = 0; i < betSlots.length; i++) {
            ItemStack betButton = new ItemStack(Material.GOLD_INGOT);
            ItemMeta betMeta = betButton.getItemMeta();
            if (betMeta != null) {
                betMeta.setDisplayName(ChatColor.GREEN + "Einsatz " + betAmounts[i]);
                betButton.setItemMeta(betMeta);
            }
            inventory.setItem(betSlots[i], betButton);
        }

        // Fill the rest of the inventory with placeholder glass panes
        fillPlaceholders();
    }

    // Update the inventory after a bet is selected
    public void updateAfterBetSelection() {
        inventory.clear(); // Clear the inventory

        // Add player icon
        ItemStack player = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta playerMeta = player.getItemMeta();
        if (playerMeta != null) {
            playerMeta.setDisplayName(ChatColor.GOLD + "Player");
            player.setItemMeta(playerMeta);
        }
        inventory.setItem(49, player);

        // Add dealer icon
        ItemStack dealer = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta dealerMeta = dealer.getItemMeta();
        if (dealerMeta != null) {
            dealerMeta.setDisplayName(ChatColor.RED + "Dealer");
            dealer.setItemMeta(dealerMeta);
        }
        inventory.setItem(4, dealer);

        // Add dealer's card placeholder
        ItemStack dealerCard = new ItemStack(Material.BLACK_STAINED_GLASS);
        ItemMeta dealerCardMeta = dealerCard.getItemMeta();
        if (dealerCardMeta != null) {
            dealerCardMeta.setDisplayName(ChatColor.DARK_GRAY + "Dealer Card Placeholder");
            dealerCard.setItemMeta(dealerCardMeta);
        }
        inventory.setItem(13, dealerCard);

        // Add player's card placeholder
        ItemStack playerCard = new ItemStack(Material.BLACK_STAINED_GLASS);
        ItemMeta playerCardMeta = playerCard.getItemMeta();
        if (playerCardMeta != null) {
            playerCardMeta.setDisplayName(ChatColor.DARK_GRAY + "Player Card Placeholder");
            playerCard.setItemMeta(playerCardMeta);
        }
        inventory.setItem(40, playerCard);

        // Fill the rest of the inventory with placeholder glass panes
        fillPlaceholders();
    }

    // Fill empty slots with black stained glass panes
    private void fillPlaceholders() {
        ItemStack placeholder = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta placeholderMeta = placeholder.getItemMeta();
        if (placeholderMeta != null) {
            placeholderMeta.setDisplayName(" ");
            placeholder.setItemMeta(placeholderMeta);
        }

        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, placeholder);
            }
        }
    }

    // Get the inventory object
    public Inventory getInventory() {
        return inventory;
    }
}
