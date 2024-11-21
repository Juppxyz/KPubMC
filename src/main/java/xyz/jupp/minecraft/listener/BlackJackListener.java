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
    ArrayList<SimpleEntry<String, Integer>> deck = createDeck(); // Convert deck to ArrayList

    ArrayList<SimpleEntry<String, Integer>> dealerCards = new ArrayList<>();
    ArrayList<SimpleEntry<String, Integer>> playerCards = new ArrayList<>();
    private final HashMap<UUID, Integer> playerBets = new HashMap<>();

    private String gameState = "start";

    private final int[] dealerSlots = {13, 12, 11, 10, 9};
    private final int[] playerSlots = {42, 41, 40, 39, 38, 37};


    private static ArrayList<SimpleEntry<String, Integer>> createDeck() {
        // Card names and values
        String[] cardNames = {"Ace", "2", "3", "4", "5", "6", "7", "8", "9", "10", "Jack", "Queen", "King"};
        int[] cardValues = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10, 10}; // Aces are 1; face cards are 10

        // Deck as an ArrayList
        ArrayList<SimpleEntry<String, Integer>> deck = new ArrayList<>();

        // Build the deck
        for (int i = 0; i < cardNames.length; i++) {
            for (int j = 0; j < 4; j++) { // Four copies of each card
                deck.add(new SimpleEntry<>(cardNames[i], cardValues[i]));
            }
        }
        System.out.println(deck);
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
        hand.add(card); // Append card to the end of the hand
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

                // Set game state to "betting"
                gameState = "betting";
            }
        }
    }


    @NotNull
    private static ItemStack createNewItem(Material material, String name) {
        ItemStack itemStack = new ItemStack(material);
        return itemStack;
    }

    public void startGame(Player player) {
        if (!gameState.equals("start")) {
            return;
        }

        gameState = "in_progress";

        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            // Dealer's first card
            drawDealer(deck, dealerCards);
            activeGames.get(player.getUniqueId()).renderCard(dealerCards.get(0).getKey(), dealerSlots[0]);
            System.out.println("Dealer draws: " + dealerCards.get(0).getKey());
        }, 0L); // No delay for the first card

        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            // Player's first card
            drawPlayer(deck, playerCards);
            activeGames.get(player.getUniqueId()).renderCard(playerCards.get(0).getKey(), playerSlots[0]);
            System.out.println("Player draws: " + playerCards.get(0).getKey());
        }, 10L); // 500ms delay (10 ticks = 500ms)

        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            // Dealer's second card (covered)
            drawDealer(deck, dealerCards);
            activeGames.get(player.getUniqueId()).renderCard("", dealerSlots[1]); // Covered card
            System.out.println("Dealer draws: [covered]");
        }, 20L); // 1-second delay

        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            // Player's second card
            drawPlayer(deck, playerCards);
            activeGames.get(player.getUniqueId()).renderCard(playerCards.get(1).getKey(), playerSlots[1]);
            System.out.println("Player draws: " + playerCards.get(1).getKey());
        }, 30L); // 1.5-second delay

        System.out.println("Game started: Cards are being dealt.");
    }

    private int findNextAvailableSlot(int[] slots, int cardCount) {
        if (cardCount <= slots.length) {
            return slots[cardCount - 1]; // Return the appropriate slot based on card count
        }
        throw new IllegalArgumentException("Too many cards for available slots.");
    }


    private void playDealerTurn(Player player, BlackJackInventory blackJackInventory) {
        player.sendMessage(ChatColor.YELLOW + "Dealer's turn...");
        while (calculateHandValue(dealerCards) < 17) {
            drawDealer(deck, dealerCards); // Dealer draws a card
            int dealerCardSlot = findNextAvailableSlot(dealerSlots, dealerCards.size());
            blackJackInventory.renderCard(dealerCards.get(dealerCards.size() - 1).getKey(), dealerCardSlot);
            System.out.println("Dealer drew: " + dealerCards.get(dealerCards.size() - 1).getKey());
        }

        int dealerValue = calculateHandValue(dealerCards);
        int playerValue = calculateHandValue(playerCards);

        // Determine the winner
        if (dealerValue > 21 || playerValue > dealerValue) {
            player.sendMessage(ChatColor.GREEN + "You win!");
        } else if (playerValue == dealerValue) {
            player.sendMessage(ChatColor.YELLOW + "It's a tie!");
        } else {
            player.sendMessage(ChatColor.RED + "Dealer wins!");
        }

        endGame(player, true);
    }


    private void endGame(Player player, boolean isDealerTurn) {
        activeGames.remove(player.getUniqueId()); // Remove the player from active games
        player.closeInventory(); // Close the inventory
        player.sendMessage(ChatColor.BLUE + "Game over. Thanks for playing!");

        // Optionally, reset game state
        if (!isDealerTurn) {
            dealerCards.clear();
            playerCards.clear();
            deck = createDeck();
            gameState = "start";
        }
    }

    private int calculateHandValue(ArrayList<SimpleEntry<String, Integer>> hand) {
        int value = 0;
        int aceCount = 0;

        for (SimpleEntry<String, Integer> card : hand) {
            value += card.getValue();
            if (card.getKey().equalsIgnoreCase("Ace")) {
                aceCount++;
            }
        }

        // Adjust for Aces (Ace can be 1 or 11)
        while (value > 21 && aceCount > 0) {
            value -= 10; // Convert an Ace from 11 to 1
            aceCount--;
        }

        return value;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        Inventory inventory = event.getClickedInventory();

        // Ensure the clicked inventory is part of an active blackjack game
        if (inventory != null && activeGames.containsKey(player.getUniqueId())) {
            event.setCancelled(true); // Prevent default behavior for other interactions

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getItemMeta() == null) return;

            // Get the active game for the player
            BlackJackInventory blackJackInventory = activeGames.get(player.getUniqueId());

            // Bet Selection Phase
            if (gameState.equals("betting")) {
                if (clickedItem.getType() == Material.GOLD_INGOT) { // Check if clicked item is a GOLD_INGOT
                    String displayName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
                    if (displayName.startsWith("Einsatz")) {
                        try {
                            int betAmount = Integer.parseInt(displayName.split(" ")[1]);

                            // Store the bet amount
                            playerBets.put(player.getUniqueId(), betAmount);
                            player.sendMessage(ChatColor.GREEN + "You have placed a bet of " + betAmount + "!");

                            // Transition to the next phase
                            gameState = "start";

                            // Update the inventory for the game phase
                            blackJackInventory.updateAfterBetSelection();
                            player.openInventory(blackJackInventory.getInventory());

                            // Start the game
                            startGame(player);
                        } catch (NumberFormatException e) {
                            player.sendMessage(ChatColor.RED + "Invalid bet amount!");
                        }
                    }
                    return;
                }
            }

            // Game Phase: Handle Player Actions
            if (gameState.equals("in_progress")) {
                ItemMeta meta = clickedItem.getItemMeta();
                PersistentDataContainer data = meta.getPersistentDataContainer();

                // Check for "Hit" action
                if (data.has(blackJackInventory.getHitKey(), PersistentDataType.BYTE)) {
                    drawPlayer(deck, playerCards); // Draw a card for the player
                    int playerCardSlot = findNextAvailableSlot(playerSlots, playerCards.size());
                    blackJackInventory.renderCard(playerCards.get(playerCards.size() - 1).getKey(), playerCardSlot);
                    player.sendMessage(ChatColor.GREEN + "You chose to Hit.");

                    // Check for bust (if total > 21)
                    if (calculateHandValue(playerCards) > 21) {
                        player.sendMessage(ChatColor.RED + "You busted!");
                        endGame(player, false); // End game, player loses
                    }
                    return;
                }

                // Check for "Stay" action
                if (data.has(blackJackInventory.getStayKey(), PersistentDataType.BYTE)) {
                    player.sendMessage(ChatColor.YELLOW + "You chose to Stay.");
                    playDealerTurn(player, blackJackInventory); // Let the dealer take its turn
                    return;
                }

                // Check for "Double" action
                if (data.has(blackJackInventory.getDoubleKey(), PersistentDataType.BYTE)) {
                    player.sendMessage(ChatColor.GREEN + "You chose to Double.");
                    drawPlayer(deck, playerCards); // Draw one card for the player
                    int playerCardSlot = findNextAvailableSlot(playerSlots, playerCards.size());
                    blackJackInventory.renderCard(playerCards.get(playerCards.size() - 1).getKey(), playerCardSlot);

                    // End player's turn after doubling
                    playDealerTurn(player, blackJackInventory);
                    return;
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
