package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.config.ShopItem;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.inventory.JewelerInventory;
import xyz.jupp.minecraft.inventory.ShopInventory;
import xyz.jupp.minecraft.utils.BlackMarketHandler;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.RedeemableItems;

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShopListener implements Listener {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ShopListener.class);

    //@EventHandler
    //public void onInteractWithShopChest(PlayerInteractEvent event) {
    //    if (!(event.getAction().equals(Action.LEFT_CLICK_BLOCK) || event.getAction().equals(Action.RIGHT_CLICK_BLOCK))) return;
    //    if (!(event.getClickedBlock() != null && event.getClickedBlock().getType().equals(Material.RESPAWN_ANCHOR))) return;
    //    event.setCancelled(true);
    //    Player player = event.getPlayer();
    //    ShopInventory.openInventory(player, ShopInventory.ShopInventoryTypes.MAIN);
    //}


    @EventHandler
    public void onInteractWithShopVillager(PlayerInteractEntityEvent event) {
        Entity interactedEntity = event.getRightClicked();

        if (interactedEntity.getType().equals(EntityType.VILLAGER)) {
            if (interactedEntity.isCustomNameVisible() && interactedEntity.getCustomName().equals(Main.getShopVillagerName())) {
                event.setCancelled(true);
                Player player = event.getPlayer();
                ShopInventory.openInventory(player, ShopInventory.ShopInventoryTypes.MAIN);
                return;
            }

            if (interactedEntity.isCustomNameVisible() && interactedEntity.getCustomName().equals(Main. getFinanceVillagerFredName())) {
                event.setCancelled(true);
                Player player = event.getPlayer();
                ItemStack itemStack = player.getInventory().getItemInMainHand();

                if (itemStack == null || itemStack.getType() == Material.AIR) {
                    player.sendMessage(Main.getChatPrefix() + "§fDu hast kein Bargeld in der Hand, das du einzahlen kannst.");
                    return;
                }

                // Überprüfen, ob es sich um Smaragde handelt
                if (itemStack.getType() == Material.EMERALD) {
                    ItemMeta itemMeta = itemStack.getItemMeta();

                    if (itemMeta != null && itemMeta.hasDisplayName() && itemMeta.getDisplayName().equals(Main.getCurrencyName(10))) {
                        int stackSize = itemStack.getAmount();
                        int amountToDeposit = stackSize * 10; // Jeder Emerald entspricht 10 Schilling

                        // Stack aus der Hand entfernen
                        player.getInventory().setItemInMainHand(null);

                        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                            PlayerCollection playerCollection = new PlayerCollection(player);
                            int currentMoney = playerCollection.getMoney();
                            playerCollection.updateMoney(currentMoney + amountToDeposit);

                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                            Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "deposit from " + player.getUniqueId() + " (" + amountToDeposit + ")");
                            player.sendMessage(Main.getChatPrefix() + "§fDu hast " + Main.getCurrencyName(amountToDeposit) + " §ferfolgreich auf dein Konto eingezahlt.");
                        });
                        return;
                    }
                }

                // Wenn keine gültigen Smaragde in der Hand sind
                player.sendMessage(Main.getChatPrefix() + "§fDu kannst nur gültiges §5Bargeld §feinzahlen.");
                return;
            }

            if (interactedEntity.isCustomNameVisible() && interactedEntity.getCustomName().equals(Main.getJewelerVillagerName())) {
                event.setCancelled(true);
                Player player = event.getPlayer();
                JewelerInventory.openInventory(player, JewelerInventory.JewelerInventoryType.MAIN);
                return;
            }
        }

        if (interactedEntity.getType().equals(EntityType.VINDICATOR)) {
            if (interactedEntity.isCustomNameVisible() && interactedEntity.getCustomName().equals(Main.getBlackMarketDealerVillagerName())) {
                event.setCancelled(true);
                Player player = event.getPlayer();
                boolean isMarketOpen = BlackMarketHandler.isOpen();
                if (!isMarketOpen) {
                    player.playSound(player, Sound.BLOCK_ENDER_CHEST_CLOSE, 2f, 2f);
                    player.sendMessage(Main.getBlackMarketDealerVillagerName() + " §7» §f§oIch kann dir leider gerade nix anbieten. Komm später wieder.");
                    return;
                }

                Inventory blackMarketInventory = Bukkit.createInventory(player, InventoryType.DISPENSER, "§0§oMarkt des " + Main.getBlackMarketDealerVillagerName());
                for (int i = 0; i < blackMarketInventory.getSize(); i++) {
                    if (i == 4) {
                        blackMarketInventory.setItem(i, BlackMarketHandler.getCurrentBlackMarketItem());
                        continue;
                    }
                    blackMarketInventory.setItem(i, createNewItem(Material.BLACK_STAINED_GLASS, "§8---"));
                }

                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.2f);
                player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 0.8f);
                player.openInventory(blackMarketInventory);
                return;
            }
            return;
        }

        if (interactedEntity.getType().equals(EntityType.WANDERING_TRADER)) {
            if (interactedEntity.isCustomNameVisible() && interactedEntity.getCustomName().equals(Main.getTeamPointsDealerVillagerName())) {
                event.setCancelled(true);
                Player player = event.getPlayer();
                PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                if (playerCacheObject.getTeamID() == null) {
                    player.sendMessage(Main.getChatPrefix() + "§fNur Mitglieder eines Teams können Items gegen Punkte tauschen.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    return;
                }

                ItemStack itemStack = player.getInventory().getItemInMainHand();
                int teamPoints = RedeemableItems.getPoints(itemStack.getType());

                if (itemStack == null || itemStack.getType() == Material.AIR || teamPoints == -1) {
                    player.sendMessage(Main.getChatPrefix() + "§cDu hast nix in der Hand, was du eintauschen kannst!");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    return;
                }

                int amountOfItems = itemStack.getAmount();
                int earnedTeamPoints = amountOfItems * teamPoints;

                player.getInventory().setItemInMainHand(null);

                Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                    TeamCollection teamCollection = new TeamCollection(playerCacheObject.getTeamID());
                    int currentPoints = teamCollection.getTeamPoints();
                    teamCollection.changeTeamPoints(currentPoints + earnedTeamPoints);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                    Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "add teampoints from " + currentPoints + " to " +  (currentPoints + earnedTeamPoints) + "(" + player.getUniqueId() + ")");

                    PlayerCacheObject tmpPlayerCacheObject;
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(online);
                        if (tmpPlayerCacheObject.getTeamID() == null) continue;
                        if (tmpPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                            online.sendMessage(Main.getChatPrefix() + playerCacheObject.getTeamColor() + player.getName() + " §fhat §a+" + earnedTeamPoints + " Team-Punkte §fbeim Händler eingetauscht!");
                            online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 0.2f);
                        }
                    }

                });

            }

        }

    }


    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getType().equals(EntityType.VILLAGER)) {
            Villager villager = (Villager) event.getEntity();
            if (villager.isCustomNameVisible() && villager.getCustomName().equals(Main.getShopVillagerName())) event.setCancelled(true);
            if (villager.isCustomNameVisible() && villager.getCustomName().equals(Main.getFinanceVillagerFredName())) event.setCancelled(true);
            if (villager.isCustomNameVisible() && villager.getCustomName().equals(Main.getJewelerVillagerName())) event.setCancelled(true);
            return;
        }
        if (event.getEntity().getType().equals(EntityType.VINDICATOR)) {
            Vindicator vindicator = (Vindicator) event.getEntity();
            if (vindicator.isCustomNameVisible() && vindicator.getCustomName().equals(Main.getBlackMarketDealerVillagerName())) event.setCancelled(true);
            return;
        }
        if (event.getEntity().getType().equals(EntityType.WANDERING_TRADER)) {
            WanderingTrader wTrader = (WanderingTrader) event.getEntity();
            if (wTrader.isCustomNameVisible() && wTrader.getCustomName().equals(Main.getTeamPointsDealerVillagerName())) event.setCancelled(true);
            return;
        }

    }


    @EventHandler
    public void onClick(InventoryClickEvent event) {
        @NotNull InventoryView inventory = event.getView();
        String title = inventory.getTitle();
        HumanEntity entity = event.getWhoClicked();
        if (entity instanceof Player) {
            Player player = (Player) entity;

            if (title.contains(Main.getShopVillagerName())) {
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

            if (title.contains("§8Tresen des %s's".formatted(Main.getJewelerVillagerName()))) {
                event.setCancelled(true);
                Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                    ItemStack clickedItem = event.getCurrentItem();
                    if (clickedItem == null) return;
                    String displayName = clickedItem.getItemMeta().getDisplayName();
                    if (displayName.equals("§7---")) return;

                    int cost = 0;
                    Material gettingMaterial = null;

                    if (displayName.startsWith("§aSmaragd")){
                        cost = 50;
                        gettingMaterial = Material.EMERALD;
                    }else if (displayName.startsWith("§eGold")) {
                        cost = 100;
                        gettingMaterial = Material.GOLD_INGOT;
                    }else if (displayName.startsWith("§bDiamant")) {
                        cost = 250;
                        gettingMaterial = Material.DIAMOND;
                    }else if (displayName.startsWith("§8Netherite")) {
                        cost = 1000;
                        gettingMaterial = Material.NETHERITE_INGOT;
                    }else if (displayName.startsWith("§5Amethyst")) {
                        cost = 200;
                        gettingMaterial = Material.AMETHYST_SHARD;
                    }else if (displayName.startsWith("§6Harz")) {
                        cost = 300;
                        gettingMaterial = Material.RESIN_CLUMP;
                    }else if (displayName.startsWith("§9Lapislazuli")) {
                        cost = 50;
                        gettingMaterial = Material.LAPIS_LAZULI;
                    }
                    if (gettingMaterial == null) return;

                    // tax
                    double tax = cost + (ConfigManager.getManager().getTradeTax() * cost);
                    cost = Math.toIntExact(Math.round(tax));

                    PlayerCollection playerCollection = new PlayerCollection(player);
                    int money = playerCollection.getMoney();

                    if (money < cost) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                        player.sendMessage(Main.getChatPrefix() + "§cDu hast leider nicht genügend Geld.");
                    }else  {
                        playerCollection.updateMoney(money - cost);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                        String itemName = clickedItem.getItemMeta().getDisplayName().split(" ")[0];
                        player.getInventory().addItem(new ItemStack(gettingMaterial, 1));
                        player.sendMessage(Main.getChatPrefix() + "§fDu hast erfolgreich " + itemName + " §fgekauft!");
                        player.sendMessage(Main.getChatPrefix() + "§c-" + cost + " Schilling");
                    }
                    return;
                });
                return;
            }

            if (title.contains("§0§oMarkt des " + Main.getBlackMarketDealerVillagerName())) {
                event.setCancelled(true);
                Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                    if (event.getCurrentItem() == null || !event.getCurrentItem().getType().equals(BlackMarketHandler.getCurrentBlackMarketItem().getType())) return;

                    PlayerCollection playerCollection = new PlayerCollection(player);
                    int money = playerCollection.getMoney();
                    int costs = BlackMarketHandler.getCurrentCosts().get();

                    if (money < costs) {
                        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                        player.sendMessage(Main.getBlackMarketDealerVillagerName() + " §7» §f§oPuh, dafür will ich mehr Schillinge als du hast, verzieh dich!");
                        return;
                    }

                    playerCollection.updateMoney(money - costs);
                    player.getInventory().addItem(BlackMarketHandler.getCurrentBlackMarketItem());
                    player.sendMessage(Main.getChatPrefix() + "§c-%d%s".formatted(BlackMarketHandler.getCurrentCosts().get(), Main.getCurrencyName()));
                    player.sendMessage(Main.getBlackMarketDealerVillagerName() + " §7» §f§oBesuche mich gerne bald wieder! Viel Spaß damit.");
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f,0.2f);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 2f,2f);

                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        player.closeInventory();
                        BlackMarketHandler.forceReroll();
                    });
                });
                return;
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
        playerCollection.getPlayer().sendMessage(Main.getChatPrefix() + "§a" + Main.getCurrencyName(price));
        Logger.console(Main.getConsolePrefix() + "player §a" + playerCollection.getPlayer().getUniqueId() + " §fhas §6sold §f" + material.name() + " for §a" + price);
        return true;
    }


    private boolean buyItems(PlayerCollection playerCollection, Material material, String name, int amount, int price) {
        int money = playerCollection.getMoney();
        if (money < price) return false;

        ItemStack itemStack = createNewItem(material, name);
        itemStack.setAmount(amount);
        playerCollection.updateMoney(money - price);
        playerCollection.getPlayer().sendMessage(Main.getChatPrefix() + "§c-" + price + " Schilling");
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
