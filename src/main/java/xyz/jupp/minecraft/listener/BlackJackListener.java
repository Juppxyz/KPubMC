package xyz.jupp.minecraft.listener;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.inventory.BlackJackInventory;
import java.util.AbstractMap.SimpleEntry;
import java.util.*;

public class BlackJackListener implements Listener {

    private final HashMap<UUID, BlackJackInventory> activeGames = new HashMap<>();
    SimpleEntry<String, Integer>[] deck = createDeck();
    // help
    // Create dealer and player card arrays
    ArrayList<SimpleEntry<String, Integer>> dealerCards = new ArrayList<>();
    ArrayList<SimpleEntry<String, Integer>> playerCards = new ArrayList<>();


    private static SimpleEntry<String, Integer>[] createDeck() {
        // Card names and values
        String[] cardNames = {"Ace", "2", "3", "4", "5", "6", "7", "8", "9", "10", "Jack", "Queen", "King"};
        int[] cardValues = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10, 10}; // Aces are 1; face cards are 10

        // Array to hold the deck
        SimpleEntry<String, Integer>[] deck = new SimpleEntry[52];

        int index = 0;

        // Build the deck
        for (int i = 0; i < cardNames.length; i++) {
            for (int j = 0; j < 4; j++) { // Four copies of each card
                deck[index++] = new SimpleEntry<>(cardNames[i], cardValues[i]);
            }
        }
        System.out.println(Arrays.toString(deck));
        return deck;
    }


    public static void drawDealer(ArrayList<SimpleEntry<String, Integer>> deck, ArrayList<SimpleEntry<String, Integer>> dealerCards) {
        drawCard(deck, dealerCards);
    }

    public static void drawPlayer(ArrayList<SimpleEntry<String, Integer>> deck, ArrayList<SimpleEntry<String, Integer>> playerCards) {
        drawCard(deck, playerCards);
    }

    private static void drawCard(ArrayList<SimpleEntry<String, Integer>> deck, ArrayList<SimpleEntry<String, Integer>> hand) {
        if (deck.isEmpty()) {
            System.out.println("Deck is empty. No more cards to draw.");
            return;
        }
        Random random = new Random();
        int index = random.nextInt(deck.size()); // Select a random card
        SimpleEntry<String, Integer> card = deck.remove(index); // Remove card from deck
        hand.add(card); // Add card to the player's or dealer's hand
    }

    private static void printHand(ArrayList<SimpleEntry<String, Integer>> hand) {
        for (SimpleEntry<String, Integer> card : hand) {
            System.out.println(card.getKey() + " - " + card.getValue());
        }
    }


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

            // check for hit click
            if (clickedItem.getType() == Material.DIAMOND) {
                ItemMeta meta = clickedItem.getItemMeta();
                PersistentDataContainer data = meta.getPersistentDataContainer();
                if (data.has(new NamespacedKey(Main.getInstance(), "control_hit"), PersistentDataType.BYTE)) {
                    blackJackInventory.renderCard(10, 14);



                }
            }




        }
    }
    /*
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();

        // remove player from activegames
        if (activeGames.containsKey(player.getUniqueId())) {
            activeGames.remove(player.getUniqueId());
        }
    }

     */
}
