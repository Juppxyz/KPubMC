package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.economy.Bank;
import xyz.jupp.minecraft.economy.TaxClass;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.economy.Treasury;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class MoneyInventoryListener implements Listener {

    // send menu: the selected amount is the name of the diamond, the button slot shows "Abheben" or the selected receiver
    private static final int AMOUNT_SLOT = 48;
    private static final int ACTION_SLOT = 52;

    // a transfer or withdrawal of the player runs, or ended a moment ago: a double click (two click events, often
    // after the booking is done) must not book twice. Player -> no new booking before this time.
    private static final Map<UUID, Long> BOOKED_UNTIL = new ConcurrentHashMap<>();
    private static final long BOOKING_COOLDOWN_MILLIS = 500;
    // amount clicks while the balance is being read join that read (main thread only)
    private static final Map<UUID, List<Integer>> PENDING_AMOUNTS = new HashMap<>();

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (Menu.is(event.getInventory(), Menu.Type.MONEY)) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PENDING_AMOUNTS.remove(event.getPlayer().getUniqueId());
        BOOKED_UNTIL.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!Menu.is(event.getInventory(), Menu.Type.MONEY)) return;
        event.setCancelled(true);
        // the buttons only, with a plain click (a double click also sends a collect-to-cursor click)
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;

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

        // more receivers than fit: the next page, in place
        if (displayName.equals(MoneyInventory.nextPageName) && event.getSlot() == MoneyInventory.PAGE_SLOT) {
            Menu menu = Menu.of(view.getTopInventory());
            MoneyInventory.showReceivers(player, view.getTopInventory(), menu == null ? 0 : menu.getPage() + 1);
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 2f, 2f);
            return;
        }

        // Select user, by the exact name (an online player; no name lookup)
        if (displayName.startsWith("§2§o") && clickedItem.getType() == Material.PLAYER_HEAD) {
            Player target = Bukkit.getPlayerExact(displayName.replace("§2§o", ""));
            if (target == null) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "§fDer Spieler ist nicht mehr online.");
                return;
            }
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

        // Change amount, capped by the balance (read async); clicks during a read are applied with its result, one by one
        int addedAmount = amountButton(displayName);
        if (addedAmount != 0) {
            UUID uuid = player.getUniqueId();
            List<Integer> pending = PENDING_AMOUNTS.get(uuid);
            if (pending != null) {
                pending.add(addedAmount);
                return;
            }
            List<Integer> clicks = new ArrayList<>(List.of(addedAmount));
            PENDING_AMOUNTS.put(uuid, clicks);
            Inventory top = view.getTopInventory();
            Tasks.async(() -> {
                int money;
                try {
                    money = PlayerRepository.getMoney(player);
                } catch (RuntimeException e) {
                    MainThread.run(() -> PENDING_AMOUNTS.remove(uuid, clicks));
                    throw e;
                }
                MainThread.run(() -> {
                    PENDING_AMOUNTS.remove(uuid, clicks);
                    for (int delta : clicks) changeAmount(player, top, delta, money);
                });
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

            if ((selectedAmount - Taxes.taxOn(selectedAmount, TaxClass.STANDARD)) / 10 * 10 <= 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "Nach der Steuer bleibt kein ganzer Schein übrig, heb etwas mehr ab.");
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
            // the exact name: getPlayer(String) matches name prefixes, the money could go to someone else
            Player targetPlayer = Bukkit.getPlayerExact(lore.get(0).replace("§2§o", ""));
            if (selectedAmount < 1) return;

            if (targetPlayer != null) {
                transfer(player, targetPlayer, selectedAmount, view);
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "§fDer Spieler ist nicht mehr online.");
            }
        }
    }

    // main thread: one booking per player at a time and a short pause after it; false if refused
    private static boolean startBooking(UUID player, Runnable work) {
        Long until = BOOKED_UNTIL.get(player);
        if (until != null && System.currentTimeMillis() < until) return false;
        BOOKED_UNTIL.put(player, Long.MAX_VALUE);
        try {
            Tasks.async(() -> {
                try {
                    work.run();
                } finally {
                    BOOKED_UNTIL.put(player, System.currentTimeMillis() + BOOKING_COOLDOWN_MILLIS);
                }
            });
        } catch (RuntimeException e) {
            BOOKED_UNTIL.remove(player);
            throw e;
        }
        return true;
    }

    // the team colour without a database read for someone who left meanwhile
    private static String colour(Player player) {
        return player.isOnline() ? CacheHandler.getInstance().getPlayerInCache(player).getTeamColor() : "§a";
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
        startBooking(player.getUniqueId(), () -> {
            // cash minus trade tax; the tax goes to the state treasury, a rest below 10 stays on the account
            Taxes.CashWithdrawal withdrawal = Taxes.withdrawCash(player.getUniqueId(), selectedAmount);
            if (!withdrawal.success()) {
                MainThread.run(() -> player.sendMessage(Main.getChatPrefix() + "Du hast nicht genügend " + Main.getCurrencyName() + "§f."));
                return;
            }
            int netAmount = withdrawal.cash();
            Logger.console("withdraw from " + player.getUniqueId() + " (" + netAmount + " cash, " + withdrawal.tax() + " tax)");

            boolean scheduled = MainThread.run(() -> {
                Bank.handOut(player.getUniqueId(), netAmount, withdrawal.tax());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "§fDu hast §2" + netAmount + " " + Main.getCurrencyName() + " §fabgehoben §7(Steuer: " + withdrawal.tax() + ")");
            });
            // server stop before the hand-out: everything back, the tax leaves the treasury again
            if (!scheduled) Taxes.refundWithdrawal(player.getUniqueId(), netAmount, withdrawal.tax());
        });
    }

    // withdraw from the sender first, the receiver is only credited on success
    private static void transfer(Player player, Player targetPlayer, int selectedAmount, InventoryView view) {
        startBooking(player.getUniqueId(), () -> {
            PlayerRepository.TransferResult result = PlayerRepository.transferMoney(player.getUniqueId(), targetPlayer.getUniqueId(), selectedAmount);

            if (result == PlayerRepository.TransferResult.INSUFFICIENT_FUNDS) {
                MainThread.run(() -> player.sendMessage(Main.getChatPrefix() + "Du hast nicht genügend " + Main.getCurrencyName() + "§f."));
                return;
            }
            if (result != PlayerRepository.TransferResult.SUCCESS) return;

            MainThread.run(() -> {
                // the balances are logged by PlayerRepository
                Bukkit.getConsoleSender().sendMessage("Transfer from §a" + player.getName() + " §fto §a" + targetPlayer.getName() + " §c" + selectedAmount);

                if (targetPlayer.isOnline()) {
                    targetPlayer.playSound(targetPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                    targetPlayer.sendMessage(Main.getChatPrefix() + colour(player) + player.getName() + " §fhat dir " + Main.getCurrencyName(selectedAmount) + " §füberwiesen.");
                }
                if (!player.isOnline()) return;
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "Du hast " + Main.getCurrencyName(selectedAmount) + " §fan " + colour(targetPlayer) + targetPlayer.getName() + " §füberwiesen.");
                view.setItem(ACTION_SLOT, createItemStack("§aÜberweisen", Material.NETHER_STAR));
            });
        });
    }
}
