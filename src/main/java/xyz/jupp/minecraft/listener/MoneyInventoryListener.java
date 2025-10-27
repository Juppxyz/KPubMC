package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.inventory.MoneyInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class MoneyInventoryListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        @NotNull InventoryView inventory = event.getView();
        String title = inventory.getOriginalTitle();
        HumanEntity entity = event.getWhoClicked();
        if (entity instanceof Player) {
            Player player = (Player) entity;
            if (title.startsWith("§aGeldbeutel §8»")) {
                event.setCancelled(true);

                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null) return;
                String displayName = clickedItem.getItemMeta().getDisplayName();

                // Close inventory
                if (displayName.equals(MoneyInventory.closeInventoryName)) {
                    player.closeInventory();
                    return;
                }

                // Open send inventory
                if (displayName.equals(MoneyInventory.moneySendName)) {
                    MoneyInventory.openInventory(player, MoneyInventory.MoneyInventoryTypes.SEND);
                    return;
                }

                // Select user
                if (displayName.startsWith("§2§o") && clickedItem.getType().equals(Material.PLAYER_HEAD)) {
                    String targetName = Bukkit.getOfflinePlayer(displayName.replace("§2§o", "")).getName();
                    ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
                    SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();

                    // Lore for better identification of the selected person
                    List<String> lores = new ArrayList<>();
                    lores.add("§2§o" + targetName);
                    playerHeadMeta.setLore(lores);

                    playerHeadMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetName));
                    playerHeadMeta.setDisplayName("§aÜberweisen");
                    playerHead.setItemMeta(playerHeadMeta);
                    inventory.setItem(52, playerHead);
                    player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 2f, 2f);
                    return;
                }

                // Change amount
                if (displayName.equals("§c- 10") || displayName.equals("§c- 1") ||
                        displayName.equals("§a+ 10") || displayName.equals("§a+ 1") ||
                        displayName.equals("§a+ 100") || displayName.equals("§c- 100")) {
                    InventoryView inventoryView = player.getOpenInventory();
                    ItemStack itemStack = inventoryView.getTopInventory().getItem(48);
                    ItemMeta itemMeta = itemStack.getItemMeta();

                    int addedAmount = Integer.parseInt(displayName.replaceAll("[^\\d-]", ""));
                    String newAmount = calculateNewAmount(itemMeta.getDisplayName(), addedAmount, player);
                    if (!newAmount.equals(itemMeta.getDisplayName())) {
                        player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f, 2f);
                    }

                    itemMeta.setDisplayName(newAmount);
                    itemStack.setItemMeta(itemMeta);
                    player.updateInventory();
                    return;
                }

                if (displayName.startsWith("§aAbheben") && clickedItem.getType().equals(Material.NETHER_STAR)) {
                    InventoryView inventoryView = player.getOpenInventory();
                    ItemStack itemStack = inventoryView.getTopInventory().getItem(48);
                    int selectedAmount = Integer.parseInt(itemStack.getItemMeta().getDisplayName());

                    if (selectedAmount < 10) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                        player.sendMessage(Main.getChatPrefix() + "Du musst mindestens " + Main.getCurrencyName(10) + " §fabheben.");
                        return;
                    }

                    if ((selectedAmount % 10) != 0) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                        player.sendMessage(Main.getChatPrefix() + "Der abzuhebende Betrag muss ein Vielfaches von 10 sein.");
                        return;
                    }

                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                        PlayerCollection playerCollection = new PlayerCollection(player);
                        int playerMoney = playerCollection.getMoney();

                        if (playerMoney < selectedAmount) {
                            player.sendMessage(Main.getChatPrefix() + "Du hast nicht genügend " + Main.getCurrencyName() + "§f.");
                            return;
                        }

                        // Steuerberechnung
                        int netAmount = (int) Math.floor(selectedAmount * (1 - ConfigManager.getManager().getTradeTax()));
                        int amountOfCash = netAmount / 10;

                        List<ItemStack> cashStacks = new ArrayList<>();
                        while (amountOfCash > 0) {
                            int stackAmount = Math.min(amountOfCash, 64);
                            ItemStack cashStack = new ItemStack(Material.EMERALD, stackAmount);
                            ItemMeta itemMeta = cashStack.getItemMeta();
                            if (itemMeta != null) {
                                List<String> lore = new ArrayList<>();
                                lore.add("§5Bargeld");
                                itemMeta.setDisplayName(Main.getCurrencyName(10));
                                itemMeta.setLore(lore);
                                cashStack.setItemMeta(itemMeta);
                            }
                            cashStacks.add(cashStack);
                            amountOfCash -= stackAmount;
                        }

                        Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "withdraw from " + player.getUniqueId() + " (" + selectedAmount + " before tax, " + netAmount + " after tax)");
                        playerCollection.updateMoney(playerMoney - selectedAmount);

                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                            for (ItemStack stack : cashStacks) {
                                HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
                                if (!leftovers.isEmpty()) {
                                    leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                                }
                            }
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                            player.sendMessage(Main.getChatPrefix() + "§fDu hast §2" + netAmount + " " + Main.getCurrencyName() + " §fabgehoben");
                        });
                    });
                    return;
                }


                // Transfer money to player
                if (displayName.equals("§aÜberweisen") && clickedItem.getType().equals(Material.PLAYER_HEAD)) {
                    String targetName = clickedItem.getItemMeta().getLore().get(0).replace("§2§o", "");
                    Player targetPlayer = Bukkit.getPlayer(targetName);

                    InventoryView inventoryView = player.getOpenInventory();
                    ItemStack itemStack = inventoryView.getTopInventory().getItem(48);
                    int selectedAmount = Integer.parseInt(itemStack.getItemMeta().getDisplayName());
                    if (selectedAmount < 1) return;

                    if (targetPlayer != null) {
                        // Offload database operations to async task
                        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                            PlayerCollection playerCollection = new PlayerCollection(player);
                            PlayerCollection targetCollection = new PlayerCollection(targetPlayer);
                            int playerMoney = playerCollection.getMoney();
                            int targetMoney = targetCollection.getMoney();

                            int newPlayerMoney = playerMoney - selectedAmount;
                            int newTargetMoney = targetMoney + selectedAmount;

                            if (newPlayerMoney < 0) {
                                // Insufficient funds, notify player on main thread
                                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                                    player.sendMessage(Main.getChatPrefix() + "Du hast nicht genügend " + Main.getCurrencyName() + "§f.");
                                });
                                return;
                            }

                            // Update money in the database
                            playerCollection.updateMoney(newPlayerMoney);
                            targetCollection.updateMoney(newTargetMoney);

                            // Log transfer
                            Bukkit.getConsoleSender().sendMessage("### Transfer Log ###");
                            Bukkit.getConsoleSender().sendMessage("Transfer from §a" + player.getName() + " §fto §a" + targetPlayer.getName() + " §c" + selectedAmount);
                            Bukkit.getConsoleSender().sendMessage(player.getName() + " §a" + playerMoney + " -> " + newPlayerMoney);
                            Bukkit.getConsoleSender().sendMessage(targetPlayer.getName() + " §a" + targetMoney + " -> " + newTargetMoney);
                            Bukkit.getConsoleSender().sendMessage("####################");

                            // Notify players on main thread
                            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                                PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                                PlayerCacheObject targetCacheObject = CacheHandler.getInstance().getPlayerInCache(targetPlayer);

                                targetPlayer.playSound(targetPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                                targetPlayer.sendMessage(Main.getChatPrefix() + playerCacheObject.getTeamColor() + player.getName() + " §fhat dir " + Main.getCurrencyName(selectedAmount) + " §füberwiesen.");
                                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                                player.sendMessage(Main.getChatPrefix() + "Du hast " + Main.getCurrencyName(selectedAmount) + " §fan " + targetCacheObject.getTeamColor() + targetPlayer.getName() + " §füberwiesen.");
                                inventory.setItem(52, createItemStack("§aÜberweisen", Material.NETHER_STAR));
                            });
                        });
                    }
                    return;
                }
            }
        }
    }

    private String calculateNewAmount(String currentAmount, int addedAmount, Player player) {
        PlayerCollection playerCollection = new PlayerCollection(player);
        int cAmount = Integer.parseInt(currentAmount);
        int sum = cAmount + addedAmount;

        if (sum < 0 || sum > playerCollection.getMoney()) {
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f, 2f);
            return currentAmount;
        }
        return String.valueOf(sum);
    }
}
