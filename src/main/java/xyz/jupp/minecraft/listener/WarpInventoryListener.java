package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.WarpCache;
import xyz.jupp.minecraft.cache.WarpCacheObject;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.inventory.WarpInventory;
import xyz.jupp.minecraft.utils.PlayerTeleport;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WarpInventoryListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        @NotNull InventoryView inventory = event.getView();
        String title = inventory.getTitle();
        HumanEntity entity = event.getWhoClicked();
        if (entity instanceof Player) {
            Player player = (Player) entity;
            if (title.startsWith("§5Warp-Menü")) {
                event.setCancelled(true);

                Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                    ItemStack clickedItem = event.getCurrentItem();
                    if (clickedItem == null) return;
                    String displayName = clickedItem.getItemMeta().getDisplayName();
                    if (displayName.equals("§7---")) return;

                    int page = 1;
                    Pattern pattern = Pattern.compile("§5Warp-Menü §8\\((\\d+)§8\\)");
                    Matcher matcher = pattern.matcher(title);
                    if (matcher.find()) {
                        page = Integer.parseInt(matcher.group(1));
                    }

                    PlayerCollection playerCollection = new PlayerCollection(player);
                    int money = playerCollection.getMoney();

                    if (displayName.equals("§cZurück")) {
                        int previousPage = page - 1;
                        if (previousPage >= 1) {
                            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                                WarpInventory.openInventory(player, 1);
                            });
                        } else {
                            player.sendMessage(Main.getChatPrefix() + "Du bist bereits auf der ersten Seite.");
                        }
                        return;
                    }

                    if (displayName.equals("§aWeiter")) {
                        int totalWarps = WarpCache.getInstance().getWarpCache().size();
                        int totalPages = (int) Math.ceil((double) totalWarps / 28);
                        int nextPage = page + 1;
                        if (nextPage <= totalPages) {
                            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                                WarpInventory.openInventory(player, nextPage);
                            });
                        } else {
                            player.sendMessage(Main.getChatPrefix() + "Du bist bereits auf der letzten Seite.");
                        }
                        return;
                    }

                    if (displayName.startsWith("§bWarp für aktuelle Position setzen")) {
                        if (money < 5000) {
                            player.sendMessage(Main.getChatPrefix() + "Der erste Kauf eines Warps kostet " + Main.getCurrencyName(5000) + "§f.");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                        }else {
                            playerCollection.updateMoney(money - 5000);
                            WarpCache.getInstance().addNewPlayerWarp(player);
                            player.sendMessage(Main.getChatPrefix() + "§aDein Warp wurde erfolgreich erstellt.");
                            player.sendMessage(Main.getChatPrefix() + "§c-5000 " + Main.getCurrencyName());
                            player.sendMessage(Main.getChatPrefix() + "§fHinweis: Bitte beachte das jeder Spieler zu diesem Warp kommen kann, immer!");
                            player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 2f,2f);
                        }
                    }

                    if (displayName.startsWith("§bWarp zu aktueller Position aktualisieren")) {
                        if (money < 5000) {
                            player.sendMessage(Main.getChatPrefix() + "Das aktualisieren deines Warps kostet " + Main.getCurrencyName(5000) + "§f.");
                            player.sendMessage(Main.getChatPrefix() + "§c-5000 " + Main.getCurrencyName());
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                        }else {
                            playerCollection.updateMoney(money - 500);
                            WarpCache.getInstance().updatePlayerWarp(player);
                            player.sendMessage(Main.getChatPrefix() + "§aDein Warp wurde erfolgreich aktualisiert.");
                            player.sendMessage(Main.getChatPrefix() + "§c-500 " + Main.getCurrencyName());
                            player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 2f,2f);
                        }

                    }

                    if (displayName.equals("§4Deinen Warp löschen")) {
                        WarpCache.getInstance().removePlayerWarp(player);
                        player.sendMessage(Main.getChatPrefix() + "Du hast deinen Warp erfolgreich gelöscht.");
                        player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 2f,2f);
                    }

                    if (displayName.startsWith("§aDein Warp")) {

                        WarpCacheObject ownWarpObject = WarpCache.getInstance().getWarpCache().get(player.getUniqueId().toString());
                        if (money < 200) {
                            player.sendMessage(Main.getChatPrefix() + "§fDas teleportieren zu deinem Warp kostet " + Main.getCurrencyName(200) + "§f.");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            return;
                        }

                        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                            PlayerTeleport playerTeleport = new PlayerTeleport();
                            Location location = new Location(Bukkit.getWorld(ownWarpObject.getWorldName()), ownWarpObject.getX(), ownWarpObject.getY(), ownWarpObject.getZ());
                            playerTeleport.teleportAfter(player, location);
                            player.sendMessage(Main.getChatPrefix() + "§c-200 " + Main.getCurrencyName());
                            playerCollection.updateMoney(money - 200);
                            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 2f,2f);

                        });

                    }

                    if (displayName.startsWith("§fWarp von§8:")) {
                        if (money < 200) {
                            player.sendMessage(Main.getChatPrefix() + "§fDas teleportieren zu einem Warp kostet " + Main.getCurrencyName(200) + "§f.");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            return;
                        }

                        ItemStack itemStack = event.getCurrentItem();
                        ItemMeta meta = itemStack.getItemMeta();
                        if ((meta instanceof SkullMeta)) {
                            SkullMeta skullMeta = (SkullMeta) meta;
                            OfflinePlayer owner = skullMeta.getOwningPlayer();
                            if (owner == null) {
                                player.sendMessage(Main.getChatPrefix() + "§cEin unerwarteter Fehler ist aufgetreten!");
                                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                                return;
                            }

                            WarpCacheObject targetWarpObject = WarpCache.getInstance().getWarpCache().get(owner.getUniqueId().toString());
                            if (targetWarpObject == null) {
                                player.sendMessage(Main.getChatPrefix() + "§cDer Warp konnte (irgendwie) nicht ermittelt werden.");
                                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                                return;
                            }

                            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                                PlayerTeleport playerTeleport = new PlayerTeleport();
                                Location location = new Location(Bukkit.getWorld(targetWarpObject.getWorldName()), targetWarpObject.getX(), targetWarpObject.getY(), targetWarpObject.getZ());
                                playerTeleport.teleportAfter(player, location);

                                player.sendMessage(Main.getChatPrefix() + "§c-200 " + Main.getCurrencyName());
                                playerCollection.updateMoney(money - 200);
                                player.sendMessage(Main.getChatPrefix() + "§fDu wurdest zum Warp von §a" + owner.getName() + " §fteleportiert.");
                            });
                        }
                    }

                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        if (player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) {
                            player.closeInventory();
                        }
                    });

                });

            }
        }

    }

}
