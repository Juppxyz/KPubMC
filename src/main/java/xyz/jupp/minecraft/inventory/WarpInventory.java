package xyz.jupp.minecraft.inventory;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.WarpCache;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.List;
import java.util.UUID;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class WarpInventory {

    public static final NamespacedKey WARP_OWNER_KEY =
            new NamespacedKey(Main.getInstance(), "warp-owner");

    public static final int WARPS_PER_PAGE = 28;

    private static final int[] MIDDLE_ROW_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    public static void openInventory(@NotNull Player player, int page) {
        Tasks.sync(() -> {
            player.closeInventory();
            if (page == 1) {
                player.openInventory(createNewMainWarpInventory(player, page));
                player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 2f, 2f);
            }
        });
    }

    private static Inventory createNewMainWarpInventory(Player player, int page) {
        Inventory inventory = Menu.create(Menu.Type.WARP, page, 54, "§5Warp-Menü §8(" + page + "§8)");

        ItemStack grayPane = createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, grayPane);
        }

        List<UUID> warpPlayerUUIDs = WarpCache.getInstance().getWarpOwners();

        int totalWarps = warpPlayerUUIDs.size();
        int totalPages = (int) Math.ceil((double) totalWarps / WARPS_PER_PAGE);
        if (totalPages < 1) totalPages = 1;

        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        int startIndex = (page - 1) * WARPS_PER_PAGE;
        int endIndex = Math.min(startIndex + WARPS_PER_PAGE, totalWarps);

        List<UUID> warpsOnPage = warpPlayerUUIDs.subList(startIndex, endIndex);

        // Warps auf der aktuellen Seite anzeigen
        for (int i = 0; i < warpsOnPage.size(); i++) {
            UUID uuid = warpsOnPage.get(i);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            if (offlinePlayer.getName() == null) continue;

            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();

            playerHeadMeta.setOwningPlayer(offlinePlayer);
            playerHeadMeta.getPersistentDataContainer().set(WARP_OWNER_KEY, PersistentDataType.STRING, uuid.toString());

            // DisplayName für den Spieler
            if (player.getUniqueId().equals(uuid)) {
                playerHeadMeta.customName(Text.of("§aDein Warp"));
            } else {
                playerHeadMeta.customName(Text.of("§fWarp von§8: §a" + offlinePlayer.getName()));
            }

            playerHead.setItemMeta(playerHeadMeta);
            inventory.setItem(MIDDLE_ROW_SLOTS[i], playerHead);
        }

        boolean hasPlayerWarp = WarpCache.getInstance().hasWarp(player.getUniqueId());
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
