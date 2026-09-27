package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.config.ShopItem;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.inventory.JewelerInventory;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.inventory.Menu;
import xyz.jupp.minecraft.inventory.ShopInventory;
import xyz.jupp.minecraft.utils.BlackMarketHandler;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.RedeemableItems;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShopListener implements Listener {

    // price in the first lore line of a shop item
    private static final Pattern PRICE_PATTERN = Pattern.compile("§fPreis: §a(\\d+) Schilling");


    @EventHandler
    public void onInteractWithShopVillager(PlayerInteractEntityEvent event) {
        Entity interactedEntity = event.getRightClicked();
        EntityType entityType = interactedEntity.getType();

        if (entityType == EntityType.VILLAGER) {
            String villagerName = visibleName(interactedEntity);
            if (Main.getShopVillagerName().equals(villagerName)) {
                event.setCancelled(true);
                ShopInventory.openInventory(event.getPlayer());
                return;
            }

            if (Main.getFinanceVillagerFredName().equals(villagerName)) {
                event.setCancelled(true);
                depositCash(event.getPlayer());
                return;
            }

            if (Main.getJewelerVillagerName().equals(villagerName)) {
                event.setCancelled(true);
                JewelerInventory.openInventory(event.getPlayer());
                return;
            }
        }

        if (entityType == EntityType.VINDICATOR) {
            if (Main.getBlackMarketDealerVillagerName().equals(Text.legacyOrNull(interactedEntity.customName()))) {
                event.setCancelled(true);
                openBlackMarket(event.getPlayer());
            }
            return;
        }

        if (entityType == EntityType.WANDERING_TRADER && Main.getTeamPointsDealerVillagerName().equals(visibleName(interactedEntity))) {
            event.setCancelled(true);
            redeemForTeamPoints(event.getPlayer());
        }
    }

    // the custom name as legacy text, null if there is none or it is not visible
    private static @Nullable String visibleName(Entity entity) {
        return entity.isCustomNameVisible() ? Text.legacyOrNull(entity.customName()) : null;
    }


    // Basil: cash in the main hand goes to the account
    private static void depositCash(Player player) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();

        if (itemStack.getType() == Material.AIR) {
            player.sendMessage(Main.getChatPrefix() + "§fDu hast kein Bargeld in der Hand, das du einzahlen kannst.");
            return;
        }

        // Überprüfen, ob es sich um Smaragde handelt
        if (itemStack.getType() == Material.EMERALD) {
            ItemMeta itemMeta = itemStack.getItemMeta();

            if (itemMeta != null && Main.getCurrencyName(10).equals(Text.legacy(itemMeta.customName()))) {
                int amountToDeposit = itemStack.getAmount() * 10; // Jeder Emerald entspricht 10 Schilling

                // Stack aus der Hand entfernen
                player.getInventory().setItemInMainHand(null);

                Tasks.async(() -> {
                    PlayerCollection.addMoney(player, amountToDeposit);
                    Logger.console("deposit from " + player.getUniqueId() + " (" + amountToDeposit + ")");
                    MainThread.run(() -> {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                        player.sendMessage(Main.getChatPrefix() + "§fDu hast " + Main.getCurrencyName(amountToDeposit) + " §ferfolgreich auf dein Konto eingezahlt.");
                    });
                });
                return;
            }
        }

        // Wenn keine gültigen Smaragde in der Hand sind
        player.sendMessage(Main.getChatPrefix() + "§fDu kannst nur gültiges §5Bargeld §feinzahlen.");
    }


    private static void openBlackMarket(Player player) {
        if (!BlackMarketHandler.isOpen()) {
            player.playSound(player, Sound.BLOCK_ENDER_CHEST_CLOSE, 2f, 2f);
            player.sendMessage(Main.getBlackMarketDealerVillagerName() + " §7» §f§oIch kann dir leider gerade nix anbieten. Komm später wieder.");
            return;
        }

        Inventory blackMarketInventory = Menu.create(Menu.Type.BLACK_MARKET, InventoryType.DISPENSER, "§0§oMarkt des " + Main.getBlackMarketDealerVillagerName());
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS);
        for (int i = 0; i < blackMarketInventory.getSize(); i++) {
            blackMarketInventory.setItem(i, i == 4 ? BlackMarketHandler.getCurrentBlackMarketItem() : glass);
        }

        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.2f);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 0.8f);
        player.openInventory(blackMarketInventory);
    }


    // Nomad: the items in the main hand become team points
    private static void redeemForTeamPoints(Player player) {
        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
        String teamID = playerCacheObject.getTeamID();
        if (teamID == null) {
            player.sendMessage(Main.getChatPrefix() + "§fNur Mitglieder eines Teams können Items gegen Punkte tauschen.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        ItemStack itemStack = player.getInventory().getItemInMainHand();
        int teamPoints = RedeemableItems.getPoints(itemStack.getType());

        if (itemStack.getType() == Material.AIR || teamPoints == -1) {
            player.sendMessage(Main.getChatPrefix() + "§cDu hast nix in der Hand, was du eintauschen kannst!");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        int earnedTeamPoints = itemStack.getAmount() * teamPoints;

        player.getInventory().setItemInMainHand(null);

        Tasks.async(() -> {
            if (!TeamCollection.addTeamPoints(teamID, earnedTeamPoints)) return;
            Logger.console("add teampoints +" + earnedTeamPoints + " (" + player.getUniqueId() + ")");

            MainThread.run(() -> {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (teamID.equals(CacheHandler.getInstance().getPlayerInCache(online).getTeamID())) {
                        online.sendMessage(Main.getChatPrefix() + playerCacheObject.getTeamColor() + player.getName() + " §fhat §a+" + earnedTeamPoints + " Team-Punkte §fbeim Händler eingetauscht!");
                        online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 0.2f);
                    }
                }
            });
        });
    }


    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        EntityType entityType = entity.getType();
        if (entityType == EntityType.VILLAGER) {
            String villagerName = visibleName(entity);
            if (Main.getShopVillagerName().equals(villagerName)
                    || Main.getFinanceVillagerFredName().equals(villagerName)
                    || Main.getJewelerVillagerName().equals(villagerName)) event.setCancelled(true);
            return;
        }
        if (entityType == EntityType.VINDICATOR) {
            if (Main.getBlackMarketDealerVillagerName().equals(Text.legacyOrNull(entity.customName()))) event.setCancelled(true);
            return;
        }
        if (entityType == EntityType.WANDERING_TRADER) {
            if (Main.getTeamPointsDealerVillagerName().equals(visibleName(entity))) event.setCancelled(true);
        }
    }


    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Menu menu = Menu.of(event.getInventory());
        if (menu == null) return;

        switch (menu.getType()) {
            case SHOP -> onShopClick(event, player);
            case JEWELER -> onJewelerClick(event, player);
            case BLACK_MARKET -> onBlackMarketClick(event, player);
            default -> {}
        }
    }


    // the click is evaluated on the main thread (sold stacks are taken here), only the bookings run async
    private static void onShopClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null) return;
        ItemMeta clickedMeta = clickedItem.getItemMeta();
        if (clickedMeta == null) return;
        String displayName = Text.legacy(clickedMeta.customName());
        if (displayName.equals("§7---")) return;

        List<String> lore = Text.legacyLore(clickedMeta.lore());
        int parsedPrice = 0;
        if (lore != null && !lore.isEmpty()) {
            Matcher matcher = PRICE_PATTERN.matcher(lore.get(0));
            if (matcher.find()) parsedPrice = Integer.parseInt(matcher.group(1));
        }
        if (parsedPrice < 1) return;
        int price = parsedPrice;

        if (displayName.equals("§5§oZufall")) {
            buyRandomItem(player, price);
            return;
        }

        int amount = clickedItem.getAmount();
        Material material = clickedItem.getType();
        if (event.getClick() == ClickType.RIGHT && lore.size() > 1 && lore.get(1).startsWith("§fVerkaufen")) {
            // taken synchronously, so fast clicks cannot sell the same stack twice
            if (!takeItemsToSell(player, material, amount)) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return;
            }
            int sellPrice = price / 2;
            Tasks.async(() -> {
                PlayerCollection.addMoney(player, sellPrice);
                Logger.console("player §a" + player.getUniqueId() + " §fhas §6sold §f" + material.name() + " for §a" + sellPrice);
                MainThread.run(() -> {
                    player.sendMessage(Main.getChatPrefix() + "§a" + Main.getCurrencyName(sellPrice));
                    player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                    player.sendMessage(Main.getChatPrefix() + String.format("Du hast §e%d §6%s §fverkauft.", amount, material.name()));
                });
            });
            return;
        }

        Tasks.async(() -> {
            if (!PlayerCollection.tryWithdrawMoney(player, price)) {
                MainThread.run(() -> {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    player.updateInventory();
                });
                return;
            }
            MainThread.deliverOrRefund(player.getUniqueId(), price, () -> {
                player.sendMessage(Main.getChatPrefix() + "§c-" + price + " Schilling");
                player.getInventory().addItem(new ItemStack(material, amount));
                Logger.console("player §a" + player.getUniqueId() + " §fhas §abought §f" + material.name() + " for §a" + price);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                player.updateInventory();
            });
        });
    }


    private static void buyRandomItem(Player player, int price) {
        Tasks.async(() -> {
            List<ShopItem> shopItems = ConfigManager.getShopItems();
            ShopItem shopItem = shopItems.isEmpty() ? null : shopItems.get(ThreadLocalRandom.current().nextInt(shopItems.size()));
            Material material = shopItem == null ? null : Material.getMaterial(shopItem.material());
            if (material == null) {
                // no usable item in the config: nothing is bought, as before (exception after the balance check)
                if (PlayerCollection.getMoney(player) < price) MainThread.run(() -> player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f));
                return;
            }

            if (!PlayerCollection.tryWithdrawMoney(player, price)) {
                MainThread.run(() -> player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f));
                return;
            }
            MainThread.deliverOrRefund(player.getUniqueId(), price, () -> {
                player.getInventory().addItem(new ItemStack(material, shopItem.amount()));
                player.sendMessage(Main.getChatPrefix() + "§c-" + price + " " + Main.getCurrencyName());
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                Logger.console("player §a" + player.getUniqueId() + " §fhas §abought §f" + shopItem.material() + " for §a" + price);
            });
        });
    }


    private static void onJewelerClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null) return;
        ItemMeta clickedMeta = clickedItem.getItemMeta();
        if (clickedMeta == null) return;
        String displayName = Text.legacy(clickedMeta.customName());

        JewelerInventory.Offer offer = JewelerInventory.Offer.byItemName(displayName);
        if (offer == null) return;

        // tax
        double tax = offer.getPrice() + (ConfigManager.getManager().getTradeTax() * offer.getPrice());
        int cost = Math.toIntExact(Math.round(tax));
        String itemName = displayName.split(" ")[0];
        Material material = offer.getMaterial();

        Tasks.async(() -> {
            if (!PlayerCollection.tryWithdrawMoney(player, cost)) {
                MainThread.run(() -> {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    player.sendMessage(Main.getChatPrefix() + "§cDu hast leider nicht genügend Geld.");
                });
                return;
            }
            MainThread.deliverOrRefund(player.getUniqueId(), cost, () -> {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                player.getInventory().addItem(new ItemStack(material, 1));
                player.sendMessage(Main.getChatPrefix() + "§fDu hast erfolgreich " + itemName + " §fgekauft!");
                player.sendMessage(Main.getChatPrefix() + "§c-" + cost + " Schilling");
            });
        });
    }


    // item and price are taken at the click, so a reroll in between cannot change what is paid or given
    private static void onBlackMarketClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        ItemStack clickedItem = event.getCurrentItem();
        ItemStack offeredItem = BlackMarketHandler.getCurrentBlackMarketItem();
        if (clickedItem == null || offeredItem == null || clickedItem.getType() != offeredItem.getType()) return;

        int costs = BlackMarketHandler.getCurrentCosts().get();

        Tasks.async(() -> {
            if (!PlayerCollection.tryWithdrawMoney(player, costs)) {
                MainThread.run(() -> {
                    player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                    player.sendMessage(Main.getBlackMarketDealerVillagerName() + " §7» §f§oPuh, dafür will ich mehr Schillinge als du hast, verzieh dich!");
                });
                return;
            }

            MainThread.deliverOrRefund(player.getUniqueId(), costs, () -> {
                player.getInventory().addItem(offeredItem);
                player.sendMessage(Main.getChatPrefix() + "§c-%d%s".formatted(costs, Main.getCurrencyName()));
                player.sendMessage(Main.getBlackMarketDealerVillagerName() + " §7» §f§oBesuche mich gerne bald wieder! Viel Spaß damit.");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f,0.2f);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 2f,2f);
                player.closeInventory();
                BlackMarketHandler.forceReroll();
            });
        });
    }


    // main thread only: removes the sold amount from the first matching stack, the credit is booked by the caller
    private static boolean takeItemsToSell(Player player, Material material, int amount) {
        Inventory inventory = player.getInventory();
        int foundIndex = -1;
        for (int i = 0; i < 46; i++) {
            ItemStack itemStack = inventory.getItem(i);
            if (itemStack == null || itemStack.getType() == Material.AIR) continue;
            if (itemStack.getType() == material && (itemStack.getAmount() >= amount)) {
                foundIndex = i;
                break;
            }
        }
        if (foundIndex == -1) return false;
        ItemStack foundItem = inventory.getItem(foundIndex);
        if (foundItem == null) return false;
        ItemMeta meta = foundItem.getItemMeta();
        if (meta != null && "§5Bargeld".equals(Text.legacy(meta.customName()))) return false;
        foundItem.setAmount(foundItem.getAmount() - amount);
        return true;
    }

}
