package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.WarpCache;
import xyz.jupp.minecraft.cache.WarpCacheObject;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.inventory.Menu;
import xyz.jupp.minecraft.inventory.WarpInventory;
import xyz.jupp.minecraft.utils.CombatLock;
import xyz.jupp.minecraft.utils.PlayerTeleport;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.UUID;

import static xyz.jupp.minecraft.inventory.WarpInventory.WARP_OWNER_KEY;

public class WarpInventoryListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Menu menu = Menu.of(event.getInventory());
        if (menu == null || menu.getType() != Menu.Type.WARP) return;
        event.setCancelled(true);

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null) return;
        ItemMeta clickedMeta = clickedItem.getItemMeta();
        // empty slot: nothing happened before either (exception)
        if (clickedMeta == null) return;
        String displayName = Text.legacy(clickedMeta.customName());
        if (displayName.equals("§7---")) return;

        int page = menu.getPage();

        if (displayName.equals("§cZurück")) {
            int previousPage = page - 1;
            if (previousPage >= 1) {
                WarpInventory.openInventory(player, 1);
            } else {
                player.sendMessage(Main.getChatPrefix() + "Du bist bereits auf der ersten Seite.");
            }
            return;
        }

        if (displayName.equals("§aWeiter")) {
            int totalWarps = WarpCache.getInstance().size();
            int totalPages = (int) Math.ceil((double) totalWarps / WarpInventory.WARPS_PER_PAGE);
            int nextPage = page + 1;
            if (nextPage <= totalPages) {
                WarpInventory.openInventory(player, nextPage);
            } else {
                player.sendMessage(Main.getChatPrefix() + "Du bist bereits auf der letzten Seite.");
            }
            return;
        }

        if (displayName.startsWith("§bWarp für aktuelle Position setzen")) {
            createWarp(player);
        } else if (displayName.startsWith("§bWarp zu aktueller Position aktualisieren")) {
            updateWarp(player);
        } else if (displayName.equals("§4Deinen Warp löschen")) {
            Tasks.async(() -> {
                WarpCache.getInstance().removePlayerWarp(player);
                MainThread.run(() -> {
                    player.sendMessage(Main.getChatPrefix() + "Du hast deinen Warp erfolgreich gelöscht.");
                    player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 2f,2f);
                    closeMenu(player);
                });
            });
        } else if (displayName.startsWith("§aDein Warp")) {
            teleportToOwnWarp(player);
        } else if (displayName.startsWith("§fWarp von§8:")) {
            teleportToWarp(player, clickedItem.getType(), clickedMeta);
        } else {
            // every other item (also in the own inventory) closes the menu, as before
            Tasks.sync(() -> closeMenu(player));
        }
    }


    // the warp caches read the player's position themselves, so they are called on the worker as before
    private static void createWarp(Player player) {
        Tasks.async(() -> {
            if (!PlayerRepository.tryWithdrawMoney(player, 5000)) {
                MainThread.run(() -> {
                    player.sendMessage(Main.getChatPrefix() + "Der erste Kauf eines Warps kostet " + Main.getCurrencyName(5000) + "§f.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    closeMenu(player);
                });
                return;
            }
            WarpCache.getInstance().addNewPlayerWarp(player);
            MainThread.run(() -> {
                player.sendMessage(Main.getChatPrefix() + "§aDein Warp wurde erfolgreich erstellt.");
                player.sendMessage(Main.getChatPrefix() + "§c-5000 " + Main.getCurrencyName());
                player.sendMessage(Main.getChatPrefix() + "§fHinweis: Bitte beachte das jeder Spieler zu diesem Warp kommen kann, immer!");
                player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 2f,2f);
                closeMenu(player);
            });
        });
    }

    private static void updateWarp(Player player) {
        Tasks.async(() -> {
            if (!PlayerRepository.tryWithdrawMoney(player, 500)) {
                MainThread.run(() -> {
                    player.sendMessage(Main.getChatPrefix() + "Das aktualisieren deines Warps kostet " + Main.getCurrencyName(500) + "§f.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    closeMenu(player);
                });
                return;
            }
            WarpCache.getInstance().updatePlayerWarp(player);
            MainThread.run(() -> {
                player.sendMessage(Main.getChatPrefix() + "§aDein Warp wurde erfolgreich aktualisiert.");
                player.sendMessage(Main.getChatPrefix() + "§c-500 " + Main.getCurrencyName());
                player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 2f,2f);
                closeMenu(player);
            });
        });
    }

    private static void teleportToOwnWarp(Player player) {
        // the menu may have been open before the fight started; checked before the price is paid
        if (CombatLock.denies(player)) return;
        WarpCacheObject ownWarpObject = WarpCache.getInstance().getWarp(player.getUniqueId());
        if (ownWarpObject == null) {
            player.sendMessage(Main.getChatPrefix() + "§cDu hast aktuell keinen gültigen Warp gesetzt.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
            return;
        }

        Tasks.async(() -> {
            if (!PlayerRepository.tryWithdrawMoney(player, 200)) {
                MainThread.run(() -> {
                    player.sendMessage(Main.getChatPrefix() + "§fDas Teleportieren zu deinem Warp kostet "
                            + Main.getCurrencyName(200) + "§f.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                });
                return;
            }

            MainThread.deliverOrRefund(player.getUniqueId(), 200, () -> {
                new PlayerTeleport().teleportAfter(player, toLocation(ownWarpObject));

                player.sendMessage(Main.getChatPrefix() + "§c-200 " + Main.getCurrencyName());
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 2f, 2f);
                closeMenu(player);
            });
        });
    }

    // the head is read here, the checks keep their former order on the worker (balance first)
    private static void teleportToWarp(Player player, Material clickedType, ItemMeta clickedMeta) {
        boolean isWarpHead = clickedType == Material.PLAYER_HEAD && clickedMeta instanceof SkullMeta;
        String ownerUuidStr = isWarpHead ? clickedMeta.getPersistentDataContainer().get(WARP_OWNER_KEY, PersistentDataType.STRING) : null;
        if (CombatLock.denies(player)) return;

        Tasks.async(() -> {
            if (PlayerRepository.getMoney(player) < 200) {
                notifyWarpError(player, "§fDas teleportieren zu einem Warp kostet " + Main.getCurrencyName(200) + "§f.");
                return;
            }
            if (!isWarpHead) return;

            if (ownerUuidStr == null) {
                notifyWarpError(player, "§cDie Warp-Daten sind beschädigt (fehlende UUID).");
                return;
            }

            UUID ownerUuid;
            try {
                ownerUuid = UUID.fromString(ownerUuidStr);
            } catch (IllegalArgumentException ex) {
                notifyWarpError(player, "§cDie Warp-Daten sind ungültig (korruptes UUID-Format).");
                return;
            }

            WarpCacheObject targetWarpObject = WarpCache.getInstance().getWarp(ownerUuid);
            if (targetWarpObject == null) {
                notifyWarpError(player, "§cDer Warp konnte (irgendwie) nicht ermittelt werden.");
                return;
            }

            if (!PlayerRepository.tryWithdrawMoney(player, 200)) {
                notifyWarpError(player, "§fDas teleportieren zu einem Warp kostet " + Main.getCurrencyName(200) + "§f.");
                return;
            }

            MainThread.deliverOrRefund(player.getUniqueId(), 200, () -> {
                new PlayerTeleport().teleportAfter(player, toLocation(targetWarpObject));

                player.sendMessage(Main.getChatPrefix() + "§c-200 " + Main.getCurrencyName());
                player.sendMessage(Main.getChatPrefix() + "§fDu wurdest zum Warp von §a" + Bukkit.getOfflinePlayer(ownerUuid).getName() + " §fteleportiert.");
                closeMenu(player);
            });
        });
    }

    private static void notifyWarpError(Player player, String message) {
        MainThread.run(() -> {
            player.sendMessage(Main.getChatPrefix() + message);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
        });
    }

    private static Location toLocation(WarpCacheObject warp) {
        return new Location(Bukkit.getWorld(warp.getWorldName()), warp.getX(), warp.getY(), warp.getZ());
    }

    private static void closeMenu(Player player) {
        if (player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) {
            player.closeInventory();
        }
    }

}
