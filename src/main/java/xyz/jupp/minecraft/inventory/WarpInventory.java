package xyz.jupp.minecraft.inventory;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.WarpCache;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class WarpInventory {

    public enum WarpInventoryTypes { MAIN }

    public static final NamespacedKey WARP_OWNER_KEY =
            new NamespacedKey(Main.getInstance(), "warp-owner");

    private static final int WARPS_PER_PAGE = 28;

    public static void openInventory(@NotNull Player player, int page) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () ->{
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                player.closeInventory();
                if (page == 1) {
                    player.openInventory(createNewMainWarpInventory(player, page));
                    player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 2f, 2f);
                }
            });
        });
    }

    private static Inventory createNewMainWarpInventory(Player player, int page) {
        Inventory inventory = Bukkit.createInventory(player, 54, "§5Warp-Menü §8(" + page + "§8)");

        ItemStack grayPane = createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, grayPane);
        }

        int[] middleRowSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        List<String> warpPlayerUUIDs = new ArrayList<>(WarpCache.getInstance().getWarpCache().keySet());

        int totalWarps = warpPlayerUUIDs.size();
        int totalPages = (int) Math.ceil((double) totalWarps / WARPS_PER_PAGE);
        if (totalPages < 1) totalPages = 1;

        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        int startIndex = (page - 1) * WARPS_PER_PAGE;
        int endIndex = Math.min(startIndex + WARPS_PER_PAGE, totalWarps);

        List<String> warpsOnPage = warpPlayerUUIDs.subList(startIndex, endIndex);

        // Warps auf der aktuellen Seite anzeigen
        for (int i = 0; i < warpsOnPage.size(); i++) {
            String warpPlayerUUID = warpsOnPage.get(i);
            UUID uuid = UUID.fromString(warpPlayerUUID);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            if (offlinePlayer == null || offlinePlayer.getName() == null) continue;

            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();

            playerHeadMeta.setOwningPlayer(offlinePlayer);

            PersistentDataContainer pdc = playerHeadMeta.getPersistentDataContainer();
            pdc.set(WARP_OWNER_KEY, PersistentDataType.STRING, uuid.toString());

            // DisplayName für den Spieler
            if (player.getUniqueId().equals(uuid)) {
                playerHeadMeta.setDisplayName("§aDein Warp");
            } else {
                playerHeadMeta.setDisplayName("§fWarp von§8: §a" + offlinePlayer.getName());
            }

            playerHead.setItemMeta(playerHeadMeta);
            inventory.setItem(middleRowSlots[i], playerHead);
        }

        boolean hasPlayerWarp = WarpCache.getInstance().getWarpCache().containsKey(player.getUniqueId().toString());
        if (hasPlayerWarp) {
            inventory.setItem(45, createItemStack("§4Deinen Warp löschen", Material.BARRIER));
        }

        if (page > 1) {
            inventory.setItem(51, createItemStack("§cZurück", Material.RED_WOOL));
        }
        if (page < totalPages) {
            inventory.setItem(52, createItemStack("§aWeiter", Material.LIME_WOOL));
        }

        String warpAction = hasPlayerWarp
                ? "§bWarp zu aktueller Position aktualisieren §8(§c-500 " + Main.getCurrencyName() + "§8)"
                : "§bWarp für aktuelle Position setzen §8(§c-5000 " + Main.getCurrencyName() + "§8)";

        inventory.setItem(53, createItemStack(warpAction, Material.NETHER_STAR));

        return inventory;
    }


}
