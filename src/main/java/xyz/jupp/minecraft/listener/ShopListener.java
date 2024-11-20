package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
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
import xyz.jupp.minecraft.inventory.ShopInventory;
import xyz.jupp.minecraft.utils.Logger;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShopListener implements Listener {

    @EventHandler
    public void onInteractWithShopChest(PlayerInteractEvent event) {
        if (!(event.getAction().equals(Action.LEFT_CLICK_BLOCK) || event.getAction().equals(Action.RIGHT_CLICK_BLOCK))) return;
        if (!(event.getClickedBlock() != null && event.getClickedBlock().getType().equals(Material.RESPAWN_ANCHOR))) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        ShopInventory.openInventory(player, ShopInventory.ShopInventoryTypes.MAIN);
    }


    @EventHandler
    public void onInteractWithShopVillager(PlayerInteractEntityEvent event) {
        Entity interactedEntity = event.getRightClicked();
        if (interactedEntity.getType().equals(EntityType.VILLAGER)) {
            if (interactedEntity.isCustomNameVisible() && interactedEntity.getCustomName().equals(Main.getShopVillagerName())) {
                event.setCancelled(true);
                Player player = event.getPlayer();
                ShopInventory.openInventory(player, ShopInventory.ShopInventoryTypes.MAIN);
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getType().equals(EntityType.VILLAGER)) {
            Villager villager = (Villager) event.getEntity();
            if (villager.isCustomNameVisible() && villager.getCustomName().equals(Main.getShopVillagerName())) event.setCancelled(true);
        }
    }


    @EventHandler
    public void onClick(InventoryClickEvent event) {
        @NotNull InventoryView inventory = event.getView();
        String title = inventory.getTitle();
        HumanEntity entity = event.getWhoClicked();
        if (entity instanceof Player) {
            Player player = (Player) entity;
            if (title.contains("§aHändler")) {
                event.setCancelled(true);
                Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                    ItemStack clickedItem = event.getCurrentItem();
                    if (clickedItem == null) return;
                    String displayName = clickedItem.getItemMeta().getDisplayName();
                    if (displayName.equals("§7---")) return;
                    int price = 0;
                    if ((clickedItem.getItemMeta().getLore() != null) && !clickedItem.getItemMeta().getLore().isEmpty()){
                        String loreLine = clickedItem.getItemMeta().getLore().get(0);
                        Pattern pattern = Pattern.compile("§fPreis: §a(\\d+) Schilling");
                        Matcher matcher = pattern.matcher(loreLine);
                        if (matcher.find()) price = Integer.parseInt(matcher.group(1));
                    }
                    if (price < 1) return;
                    PlayerCollection playerCollection = new PlayerCollection(player);

                    if (displayName.equals("§5§oZufall")) {
                        int money = playerCollection.getMoney();
                        if ( money < price) {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                            return;
                        }
                        Random random = new Random();
                        int randomIndex = random.nextInt(ConfigManager.getShopItems().size());
                        ShopItem shopItem = ConfigManager.getShopItems().get(randomIndex);
                        ItemStack itemStack = createNewItem(Material.getMaterial(shopItem.getMaterial()), shopItem.getName());
                        itemStack.setAmount(shopItem.getAmount());
                        playerCollection.updateMoney(money - price);
                        playerCollection.getPlayer().getInventory().addItem(itemStack);
                        player.sendMessage(Main.getChatPrefix() + "§c-" + price + " " + Main.getCurrencyName());
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                        Logger.console(Main.getConsolePrefix() + "player §a" + playerCollection.getPlayer().getUniqueId() + " §fhas §abought §f" + shopItem.getMaterial() + " for §a" + price);
                        return;
                    }

                    boolean sell = event.getClick().equals(ClickType.RIGHT);
                    int amount = clickedItem.getAmount();
                    @Nullable List<String> lore = clickedItem.getItemMeta().getLore();
                    if (sell && ((lore.size() > 1 && lore.get(1) != null) && lore.get(1).startsWith("§fVerkaufen"))) {
                        if (sellItems(playerCollection, clickedItem.getType(), amount, price / 2)) {
                            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                            player.sendMessage(Main.getChatPrefix() + String.format("Du hast §e%d §6%s §fverkauft.", amount, clickedItem.getType().name()));
                        }else {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                        }
                        return;
                    }

                    if (buyItems(playerCollection, clickedItem.getType(), displayName, amount, price)) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                    }else {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    }
                    player.updateInventory();
                });
            }
        }
    }


    private boolean sellItems(PlayerCollection playerCollection, Material material, int amount, int price) {
        Inventory inventory = playerCollection.getPlayer().getInventory();
        int foundIndex = -1;
        for (int i = 0; i < 46; i++) {
            ItemStack itemStack = inventory.getItem(i);
            if (itemStack == null || itemStack.getType() == null || itemStack.getType().equals(Material.AIR)) continue;
            if (itemStack.getType().equals(material) && (itemStack.getAmount() >= amount)) {
                foundIndex = i;
                break;
            }
        }
        if (foundIndex == -1) return false;
        ItemStack foundItem = inventory.getItem(foundIndex);
        if (foundItem == null) return false;
        foundItem.setAmount(foundItem.getAmount() - amount);
        playerCollection.updateMoney(playerCollection.getMoney() + price);
        Logger.console(Main.getConsolePrefix() + "player §a" + playerCollection.getPlayer().getUniqueId() + " §fhas §6sold §f" + material.name() + " for §a" + price);
        return true;
    }


    private boolean buyItems(PlayerCollection playerCollection, Material material, String name, int amount, int price) {
        int money = playerCollection.getMoney();
        if (money < price) return false;

        ItemStack itemStack = createNewItem(material, name);
        itemStack.setAmount(amount);
        playerCollection.updateMoney(money - price);

        playerCollection.getPlayer().getInventory().addItem(itemStack);
        Logger.console(Main.getConsolePrefix() + "player §a" + playerCollection.getPlayer().getUniqueId() + " §fhas §abought §f" + material.name() + " for §a" + price);
        return true;
    }

    @NotNull
    private static ItemStack createNewItem(Material material, String name) {
        ItemStack itemStack = new ItemStack(material);
        return itemStack;
    }


}
