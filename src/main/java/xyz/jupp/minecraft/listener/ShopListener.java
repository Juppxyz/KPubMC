package xyz.jupp.minecraft.listener;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.economy.TaxClass;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.economy.NomadView;
import xyz.jupp.minecraft.economy.ShopView;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.inventory.JewelerInventory;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.inventory.Menu;
import xyz.jupp.minecraft.utils.BlackMarketHandler;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;


public class ShopListener implements Listener {

    @EventHandler
    public void onInteractWithShopVillager(PlayerInteractEntityEvent event) {
        Entity interactedEntity = event.getRightClicked();
        EntityType entityType = interactedEntity.getType();

        if (entityType == EntityType.VILLAGER) {
            String villagerName = visibleName(interactedEntity);
            if (Main.getShopVillagerName().equals(villagerName)) {
                event.setCancelled(true);
                ShopView.open(event.getPlayer());
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
            NomadView.open(event.getPlayer());
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
                    PlayerRepository.addMoney(player, amountToDeposit);
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
            case JEWELER -> onJewelerClick(event, player);
            case BLACK_MARKET -> onBlackMarketClick(event, player);
            default -> {}
        }
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

        String itemName = displayName.split(" ")[0];
        Material material = offer.getMaterial();

        // price plus trade tax, the tax goes to the state treasury
        Tasks.async(() -> {
            Taxes.Purchase purchase = Taxes.chargePurchase(player.getUniqueId(), offer.getPrice(), TaxClass.LUXURY);
            if (!purchase.success()) {
                MainThread.run(() -> {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    player.sendMessage(Main.getChatPrefix() + "§cDu hast leider nicht genügend Geld.");
                });
                return;
            }
            MainThread.deliverOrRefund(player.getUniqueId(), purchase.total(), () -> {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                player.getInventory().addItem(new ItemStack(material, 1));
                player.sendMessage(Main.getChatPrefix() + "§fDu hast erfolgreich " + itemName + " §fgekauft!");
                player.sendMessage(Main.getChatPrefix() + "§c-" + purchase.total() + " Schilling §8(davon " + purchase.tax() + " Steuer)");
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

        // luxury tax on top, it goes to the state treasury
        Tasks.async(() -> {
            Taxes.Purchase purchase = Taxes.chargePurchase(player.getUniqueId(), costs, TaxClass.LUXURY);
            if (!purchase.success()) {
                MainThread.run(() -> {
                    player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                    player.sendMessage(Main.getBlackMarketDealerVillagerName() + " §7» §f§oPuh, dafür will ich mehr Schillinge als du hast, verzieh dich!");
                });
                return;
            }

            MainThread.deliverOrRefund(player.getUniqueId(), purchase.total(), () -> {
                player.getInventory().addItem(offeredItem);
                player.sendMessage(Main.getChatPrefix() + "§c-%d %s §8(davon %d Steuer)".formatted(
                        purchase.total(), Main.getCurrencyName(), purchase.tax()));
                player.sendMessage(Main.getBlackMarketDealerVillagerName() + " §7» §f§oBesuche mich gerne bald wieder! Viel Spaß damit.");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f,0.2f);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 2f,2f);
                player.closeInventory();
                BlackMarketHandler.forceReroll();
            });
        });
    }

}
