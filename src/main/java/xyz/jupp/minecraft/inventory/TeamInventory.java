package xyz.jupp.minecraft.inventory;

import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.*;
import xyz.jupp.minecraft.database.TeamCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static xyz.jupp.minecraft.inventory.MoneyInventory.createItemStack;

public class TeamInventory {

    public enum TeamInventoryTypes { CREATE, MAIN, SETTINGS, INVITE }

    // wrapper
    public static void openInventory(@NotNull Player player, @NotNull TeamInventoryTypes teamInventoryTypes) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () ->{
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                player.closeInventory();
                if (teamInventoryTypes.equals(TeamInventoryTypes.MAIN)) {
                    player.openInventory(createMainTeamInventory(player, CacheHandler.getInstance().getPlayerInCache(player)));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                }
            });
        });
    }

    public static void openInventory(@NotNull Player player, @NotNull TeamInventoryTypes teamInventoryTypes, @NotNull String addition) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () ->{
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                player.closeInventory();
                if (teamInventoryTypes.equals(TeamInventoryTypes.CREATE)) {
                    player.openInventory(createNewTeamInventory(player, addition));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                }
            });
        });
    }

    public static void openInventory(@NotNull TeamInventoryTypes teamInventoryTypes, @NotNull PlayerCacheObject playerCacheObject) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () ->{
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                playerCacheObject.getPlayer().closeInventory();
                playerCacheObject.getPlayer().playSound(playerCacheObject.getPlayer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
                if (teamInventoryTypes.equals(TeamInventoryTypes.SETTINGS)) {
                    playerCacheObject.getPlayer().openInventory(createTeamRoleInventory(playerCacheObject));
                }else if (teamInventoryTypes.equals(TeamInventoryTypes.INVITE)){
                    playerCacheObject.getPlayer().openInventory(createTeamInvitesInventory(playerCacheObject));
                }
            });
        });
    }


    private static Inventory createNewTeamInventory(Player player, String teamName) {
        Inventory inventory = Bukkit.createInventory(player, 27, "§aTeam erstellen");
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
            if (i == 0) inventory.setItem(i, createItemStack("§a" + teamName, Material.PAPER));
            if (i == 2) inventory.setItem(i, createItemStack("§4Rot", Material.RED_WOOL));
            if (i == 3) inventory.setItem(i, createItemStack("§cHell Rot", Material.RED_TERRACOTTA));
            if (i == 4) inventory.setItem(i, createItemStack("§6Orange/Gold", Material.ORANGE_WOOL));
            if (i == 5) inventory.setItem(i, createItemStack("§eGelb", Material.YELLOW_WOOL));
            if (i == 6) inventory.setItem(i, createItemStack("§2Dunkel Grün", Material.GREEN_WOOL));
            if (i == 11) inventory.setItem(i, createItemStack("§bAqua", Material.LIGHT_BLUE_WOOL));
            if (i == 12) inventory.setItem(i, createItemStack("§3Dunkel Aqua", Material.CYAN_WOOL));
            if (i == 13) inventory.setItem(i, createItemStack("§1Dunkel Blau", Material.BLUE_WOOL));
            if (i == 14) inventory.setItem(i, createItemStack("§9Blau", Material.BLUE_WOOL));
            if (i == 15) inventory.setItem(i, createItemStack("§dHelles Pink", Material.PINK_WOOL));
            if (i == 20) inventory.setItem(i, createItemStack("§5Lila", Material.PURPLE_WOOL));
            if (i == 21) inventory.setItem(i, createItemStack("§fWeiß", Material.WHITE_WOOL));
            if (i == 22) inventory.setItem(i, createItemStack("§7Grau", Material.LIGHT_GRAY_WOOL));
            if (i == 23) inventory.setItem(i, createItemStack("§8Dunkel Grau", Material.GRAY_WOOL));
            if (i == 24) inventory.setItem(i, createItemStack("§0Schwarz", Material.BLACK_WOOL));
            if (i == 26) inventory.setItem(i, createItemStack("§a§lTeam gründen", Material.NETHER_STAR));
        }
        return inventory;
    }


    private static Inventory createMainTeamInventory(Player player, PlayerCacheObject playerCacheObject) {
        Inventory inventory = Bukkit.createInventory(player, 9, playerCacheObject.getTeamColor() +"§nTeam-Menü");
        boolean isOwner = playerCacheObject.getTeamCacheObject().getTeamOwner().equals(player.getUniqueId().toString());
        boolean isVice =  playerCacheObject.getTeamCacheObject().getTeamVices().contains(player.getUniqueId().toString());
        @NotNull String teamID = playerCacheObject.getTeamID();
        TeamCollection teamCollection = new TeamCollection(teamID);

        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, createItemStack( "§7---", Material.GRAY_STAINED_GLASS_PANE));
            if (i == 1) {
                if (playerCacheObject.getTeamCacheObject().getTeamOwner().equals(player.getUniqueId().toString())) {
                    inventory.setItem(i, createItemStack(
                            "Deine Rolle: "+ playerCacheObject.getTeamColor() + "§lBoss",
                            Material.DIAMOND_SWORD
                    ));
                    continue;
                }
                ArrayList<String> teamVices = playerCacheObject.getTeamCacheObject().getTeamVices();
                if (teamVices.contains(player.getUniqueId().toString())) {
                    inventory.setItem(i, createItemStack(
                            "§fDeine Rolle: "+ playerCacheObject.getTeamColor() + "§oVize",
                            Material.GOLDEN_SWORD
                    ));
                    continue;
                }
                inventory.setItem(i, createItemStack(
                        "§fDeine Rolle: "+ playerCacheObject.getTeamColor() + "Mitglied",
                        Material.GOLDEN_SWORD
                ));
                continue;
            }

            if (i == 3) {
                if (isOwner || isVice) {
                    inventory.setItem(i, createItemStack(playerCacheObject.getTeamColor() + "Mitglied hinzufügen", Material.PAPER));
                }
                continue;
            }

            if (i == 4){
                TeamBlockCacheObject teamBlockCacheObject = TeamBlockCache.getTeamBlock(playerCacheObject.getTeamID());
                if (playerCacheObject.getTeamCacheObject().getAlreadyPurchased() > 0 && ( teamBlockCacheObject != null && teamBlockCacheObject.isActive())) {
                    inventory.setItem(i, createItemStack("TeamPunkte: " + playerCacheObject.getTeamColor() + teamCollection.getTeamPoints(), Material.GOLD_INGOT));
                }else {
                    if (isOwner && (teamBlockCacheObject == null || !teamBlockCacheObject.isActive())) {
                        ItemStack itemStack = createItemStack(playerCacheObject.getTeamColor() + "TeamBlock", Material.BEACON);
                        ItemMeta itemMeta = itemStack.getItemMeta();
                        ArrayList<String> lores = new ArrayList<>(1);
                        lores.add("§fFür " + Main.getCurrencyName(2500 + (playerCacheObject.getTeamCacheObject().getAlreadyPurchased() * 2500)) + " §fkaufen?");
                        itemMeta.setLore(lores);
                        itemStack.setItemMeta(itemMeta);
                        inventory.setItem(i, itemStack);
                    }
                }
                continue;
            }

            if(i==5 && isOwner ) {
                if (playerCacheObject.getTeamCacheObject().getMembersList().size() > 1) {
                    inventory.setItem(i, createItemStack( playerCacheObject.getTeamColor() + "Rollen", Material.GOLDEN_HELMET));
                }
                continue;
            }
            if (i==7 && !isOwner) inventory.setItem(i, createItemStack( playerCacheObject.getTeamColor() + "§4Team verlassen", Material.DARK_OAK_DOOR));
            if (i==8) inventory.setItem(i, createItemStack( playerCacheObject.getTeamColor() + "§cBye", Material.BARRIER));

        }
        return inventory;
    }


    private static Inventory createTeamInvitesInventory(PlayerCacheObject playerCacheObject) {
        Inventory inventory = Bukkit.createInventory(playerCacheObject.getPlayer(), 18, playerCacheObject.getTeamColor() + "§nNeues Mitglied");
        inventory.setItem(0, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(8, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(9, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(17, createItemStack("§cZurück", Material.BARRIER));

        PlayerCacheObject tmpPlayerCacheObject = null;
        ItemStack playerHead = null;
        int itemSlot = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            if (tmpPlayerCacheObject.isTeamInvites() && tmpPlayerCacheObject.getTeamID() == null) {
                itemSlot++;
                if (itemSlot>15)break;
                if (itemSlot==8) itemSlot = 10;

                playerHead = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();
                playerHeadMeta.setOwningPlayer(Bukkit.getOfflinePlayer(player.getName()));
                String displayName = player.getName();
                playerHeadMeta.setDisplayName("§a" + displayName);
                playerHead.setItemMeta(playerHeadMeta);
                inventory.setItem(itemSlot, playerHead);
            }
        }
        return inventory;
    }


    private static Inventory createTeamRoleInventory(PlayerCacheObject playerCacheObject) {
        Inventory inventory = Bukkit.createInventory(playerCacheObject.getPlayer(), 18, playerCacheObject.getTeamColor() + "§nTeam-Rollen");
        TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();

        inventory.setItem(0, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(8, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(9, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(17, createItemStack("§cZurück", Material.BARRIER));

        List<Document> memberList = teamCacheObject.getMembersList();
        ArrayList<String> viceList = teamCacheObject.getTeamVices();
        OfflinePlayer teamMember = null;
        ItemStack playerHead = null;

        ArrayList<String> lores = new ArrayList<>(3);
        lores.add("§fSteuerung (Maus):");
        lores.add("§aLinks  §8- §fRolle verändern");
        lores.add("§cRechts §8- §fSpieler kicken");

        int itemSlot = 0;
        for (int i = 0; i < memberList.size(); i++) {
            teamMember = Bukkit.getOfflinePlayer(UUID.fromString(memberList.get(i).getString("uuid")));
            if (teamCacheObject.getTeamOwner().equals(teamMember.getUniqueId().toString())) continue;
            itemSlot++;
            if (i == 8) itemSlot = 10;
            playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();
            playerHeadMeta.setOwningPlayer(teamMember);
            String role = viceList.contains(teamMember.getUniqueId().toString()) ? String.format("§f(%s§oVize§f) ", playerCacheObject.getTeamColor()) : "§f";
            String displayName = role + teamMember.getName();
            playerHeadMeta.setDisplayName(displayName);
            playerHeadMeta.setLore(lores);
            playerHead.setItemMeta(playerHeadMeta);
            inventory.setItem(itemSlot, playerHead);
        }
        return inventory;
    }

}
