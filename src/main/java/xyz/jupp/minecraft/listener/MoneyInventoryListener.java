package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.inventory.Menu;
import xyz.jupp.minecraft.inventory.MoneyInventory;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.List;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class MoneyInventoryListener implements Listener {

    // send menu: the selected amount is the name of the diamond, the button slot shows "Abheben" or the selected receiver
    private static final int AMOUNT_SLOT = 48;
    private static final int ACTION_SLOT = 52;

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!Menu.is(event.getInventory(), Menu.Type.MONEY)) return;
        event.setCancelled(true);

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null) return;
        ItemMeta clickedMeta = clickedItem.getItemMeta();
        // empty slot: nothing happened before either (exception)
        if (clickedMeta == null) return;
        String displayName = Text.legacy(clickedMeta.customName());
        InventoryView view = event.getView();

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
        if (displayName.startsWith("§2§o") && clickedItem.getType() == Material.PLAYER_HEAD) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(displayName.replace("§2§o", ""));
            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();

            // Lore for better identification of the selected person
            playerHeadMeta.lore(Text.lore(List.of("§2§o" + target.getName())));

            playerHeadMeta.setOwningPlayer(target);
            playerHeadMeta.customName(Text.of("§aÜberweisen"));
            playerHead.setItemMeta(playerHeadMeta);
            view.setItem(ACTION_SLOT, playerHead);
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 2f, 2f);
            return;
        }

        // Change amount, capped by the balance (read async)
        int addedAmount = amountButton(displayName);
        if (addedAmount != 0) {
            Inventory top = view.getTopInventory();
            Tasks.async(() -> {
                int money = PlayerRepository.getMoney(player);
                MainThread.run(() -> changeAmount(player, top, addedAmount, money));
            });
            return;
        }

        if (displayName.startsWith("§aAbheben") && clickedItem.getType() == Material.NETHER_STAR) {
            Integer selectedAmount = selectedAmount(view.getTopInventory());
            if (selectedAmount == null) return;

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

            withdrawCash(player, selectedAmount);
            return;
        }


        // Transfer money to player
        if (displayName.equals("§aÜberweisen") && clickedItem.getType() == Material.PLAYER_HEAD) {
            List<String> lore = Text.legacyLore(clickedMeta.lore());
            Integer selectedAmount = selectedAmount(view.getTopInventory());
            if (lore == null || lore.isEmpty() || selectedAmount == null) return;
            Player targetPlayer = Bukkit.getPlayer(lore.get(0).replace("§2§o", ""));
            if (selectedAmount < 1) return;

            if (targetPlayer != null) {
                transfer(player, targetPlayer, selectedAmount, view);
            }
        }
    }


    private static int amountButton(String displayName) {
        return switch (displayName) {
            case "§c- 100" -> -100;
            case "§c- 10" -> -10;
            case "§c- 1" -> -1;
            case "§a+ 1" -> 1;
            case "§a+ 10" -> 10;
            case "§a+ 100" -> 100;
            default -> 0;
        };
    }

    // null outside of the send menu (formerly an exception)
    private static @Nullable Integer selectedAmount(Inventory top) {
        if (top.getSize() <= AMOUNT_SLOT) return null;
        ItemStack amountItem = top.getItem(AMOUNT_SLOT);
        if (amountItem == null) return null;
        return Integer.parseInt(Text.legacy(amountItem.getItemMeta().customName()));
    }

    private static void changeAmount(Player player, Inventory top, int addedAmount, int money) {
        if (top.getSize() <= AMOUNT_SLOT) return;
        ItemStack amountItem = top.getItem(AMOUNT_SLOT);
        if (amountItem == null) return;
        ItemMeta itemMeta = amountItem.getItemMeta();
        int sum = Integer.parseInt(Text.legacy(itemMeta.customName())) + addedAmount;

        // one sound, whether the amount changes or not
        player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f, 2f);
        if (sum >= 0 && sum <= money) {
            itemMeta.customName(Text.of(String.valueOf(sum)));
            amountItem.setItemMeta(itemMeta);
        }
        player.updateInventory();
    }

    // withdrawn async, the cash is handed out on the main thread
    private static void withdrawCash(Player player, int selectedAmount) {
        Tasks.async(() -> {
            // cash minus trade tax; the tax goes to the state treasury, a rest below 10 stays on the account
            Taxes.CashWithdrawal withdrawal = Taxes.withdrawCash(player.getUniqueId(), selectedAmount);
            if (!withdrawal.success()) {
                MainThread.run(() -> player.sendMessage(Main.getChatPrefix() + "Du hast nicht genügend " + Main.getCurrencyName() + "§f."));
                return;
            }
            int netAmount = withdrawal.cash();
            Logger.console("withdraw from " + player.getUniqueId() + " (" + netAmount + " cash, " + withdrawal.tax() + " tax)");

            MainThread.deliverOrRefund(player.getUniqueId(), netAmount + withdrawal.tax(), () -> {
                int amountOfCash = netAmount / 10;
                while (amountOfCash > 0) {
                    int stackAmount = Math.min(amountOfCash, 64);
                    ItemStack cashStack = createItemStack(Main.getCurrencyName(10), Material.EMERALD, new String[]{"§5Bargeld"});
                    cashStack.setAmount(stackAmount);
                    player.getInventory().addItem(cashStack).values()
                            .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                    amountOfCash -= stackAmount;
                }
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "§fDu hast §2" + netAmount + " " + Main.getCurrencyName() + " §fabgehoben §8(+ " + withdrawal.tax() + " Steuer → Staatskasse)");
            });
        });
    }

    // withdraw from the sender first, the receiver is only credited on success
    private static void transfer(Player player, Player targetPlayer, int selectedAmount, InventoryView view) {
        Tasks.async(() -> {
            PlayerRepository.TransferResult result = PlayerRepository.transferMoney(player.getUniqueId(), targetPlayer.getUniqueId(), selectedAmount);

            if (result == PlayerRepository.TransferResult.INSUFFICIENT_FUNDS) {
                MainThread.run(() -> player.sendMessage(Main.getChatPrefix() + "Du hast nicht genügend " + Main.getCurrencyName() + "§f."));
                return;
            }
            if (result != PlayerRepository.TransferResult.SUCCESS) return;

            MainThread.run(() -> {
                // the balances are logged by PlayerRepository
                Bukkit.getConsoleSender().sendMessage("Transfer from §a" + player.getName() + " §fto §a" + targetPlayer.getName() + " §c" + selectedAmount);

                PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                PlayerCacheObject targetCacheObject = CacheHandler.getInstance().getPlayerInCache(targetPlayer);

                targetPlayer.playSound(targetPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                targetPlayer.sendMessage(Main.getChatPrefix() + playerCacheObject.getTeamColor() + player.getName() + " §fhat dir " + Main.getCurrencyName(selectedAmount) + " §füberwiesen.");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "Du hast " + Main.getCurrencyName(selectedAmount) + " §fan " + targetCacheObject.getTeamColor() + targetPlayer.getName() + " §füberwiesen.");
                view.setItem(ACTION_SLOT, createItemStack("§aÜberweisen", Material.NETHER_STAR));
            });
        });
    }
}
