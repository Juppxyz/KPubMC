package xyz.jupp.minecraft.inventory;

import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static xyz.jupp.minecraft.utils.ItemStackUtil.createItemStack;

public class TeamInventory {

    public enum TeamInventoryTypes { CREATE, MAIN, SETTINGS, INVITE }


    public static void openInventory(@NotNull Player player, @NotNull TeamInventoryTypes teamInventoryTypes) {
        Tasks.async(() -> {
            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            int teamPoints = TeamCollection.getTeamPoints(playerCacheObject.getTeamID());
            MainThread.run(() -> {
                player.closeInventory();
                TeamCacheObject team = playerCacheObject.getTeamCacheObject();
                // a team that cannot be loaded opened nothing before either (exception)
                if (!teamInventoryTypes.equals(TeamInventoryTypes.MAIN) || team == null) return;
                player.openInventory(createMainTeamInventory(player, playerCacheObject.getTeamColor(), team, teamPoints));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
            });
        });
    }


    public static void openInventory(@NotNull Player player, @NotNull TeamInventoryTypes teamInventoryTypes, @NotNull String addition) {
        Tasks.sync(() -> {
            player.closeInventory();
            if (teamInventoryTypes.equals(TeamInventoryTypes.CREATE)) {
                player.openInventory(createNewTeamInventory(addition));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
            }
        });
    }


    public static void openInventory(@NotNull TeamInventoryTypes teamInventoryTypes, @NotNull PlayerCacheObject playerCacheObject) {
        Tasks.sync(() -> {
            Player player = playerCacheObject.getPlayer();
            if (player == null) return;
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
            TeamCacheObject team = playerCacheObject.getTeamCacheObject();
            // a team that cannot be loaded opened no role menu before either (exception)
            if (teamInventoryTypes.equals(TeamInventoryTypes.SETTINGS) && team != null) {
                player.openInventory(createTeamRoleInventory(playerCacheObject.getTeamColor(), team));
            }else if (teamInventoryTypes.equals(TeamInventoryTypes.INVITE)){
                player.openInventory(createTeamInvitesInventory(playerCacheObject.getTeamColor()));
            }
        });
    }


    public static void openSettingsInventory(@NotNull Player player, @NotNull PlayerCacheObject playerCacheObject) {
        Tasks.sync(() -> {
            Player cachedPlayer = playerCacheObject.getPlayer();
            if (cachedPlayer == null) return;
            cachedPlayer.closeInventory();
            cachedPlayer.playSound(cachedPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
            TeamCacheObject team = playerCacheObject.getTeamCacheObject();
            // a team that cannot be loaded opened nothing before either (exception)
            if (team == null) return;
            player.openInventory(createAreaSettingsInventory(playerCacheObject.getTeamColor(), team));
        });
    }


    private static Inventory createNewTeamInventory(String teamName) {
        Inventory inventory = Menu.create(Menu.Type.TEAM_CREATE, 27, "§aTeam erstellen");
        ItemStack grayPane = createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, grayPane);
        }
        inventory.setItem(0, createItemStack("§a" + teamName, Material.PAPER));
        inventory.setItem(2, createItemStack("§4Rot", Material.RED_WOOL));
        inventory.setItem(3, createItemStack("§cHell Rot", Material.RED_TERRACOTTA));
        inventory.setItem(4, createItemStack("§6Orange/Gold", Material.ORANGE_WOOL));
        inventory.setItem(5, createItemStack("§eGelb", Material.YELLOW_WOOL));
        inventory.setItem(6, createItemStack("§2Dunkel Grün", Material.GREEN_WOOL));
        inventory.setItem(11, createItemStack("§bAqua", Material.LIGHT_BLUE_WOOL));
        inventory.setItem(12, createItemStack("§3Dunkel Aqua", Material.CYAN_WOOL));
        inventory.setItem(13, createItemStack("§1Dunkel Blau", Material.BLUE_WOOL));
        inventory.setItem(14, createItemStack("§9Blau", Material.BLUE_WOOL));
        inventory.setItem(15, createItemStack("§dHelles Pink", Material.PINK_WOOL));
        inventory.setItem(20, createItemStack("§5Lila", Material.PURPLE_WOOL));
        inventory.setItem(21, createItemStack("§fWeiß", Material.WHITE_WOOL));
        inventory.setItem(22, createItemStack("§7Grau", Material.LIGHT_GRAY_WOOL));
        inventory.setItem(23, createItemStack("§8Dunkel Grau", Material.GRAY_WOOL));
        inventory.setItem(24, createItemStack("§0Schwarz", Material.BLACK_WOOL));
        inventory.setItem(26, createItemStack("§a§lTeam gründen", Material.NETHER_STAR));
        return inventory;
    }


    private static Inventory createMainTeamInventory(Player player, String teamColor, TeamCacheObject team, int teamPoints) {
        Inventory inventory = Menu.create(Menu.Type.TEAM_MAIN, 9, teamColor + "§nTeam-Menü");
        boolean isOwner = team.getTeamOwner().equals(player.getUniqueId().toString());
        boolean isVice = team.getTeamVices().contains(player.getUniqueId().toString());

        ItemStack grayPane = createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, grayPane);
        }

        if (isOwner) {
            inventory.setItem(0, createItemStack("Deine Rolle: " + teamColor + "§lBoss", Material.DIAMOND_SWORD));
        } else if (isVice) {
            inventory.setItem(0, createItemStack("§fDeine Rolle: " + teamColor + "§oVize", Material.GOLDEN_SWORD));
        } else {
            inventory.setItem(0, createItemStack("§fDeine Rolle: " + teamColor + "Mitglied", Material.GOLDEN_SWORD));
        }
        inventory.setItem(1, createItemStack("§fTeam-Punkte: " + teamColor + teamPoints, Material.GOLD_INGOT));
        inventory.setItem(2, getTeamUpgradeItem(teamColor, team));
        if (isOwner || isVice) {
            inventory.setItem(3, createItemStack(teamColor + "Mitglied hinzufügen", Material.PAPER));
            inventory.setItem(4, getColoredBanner(teamColor));
        }
        if (isOwner) inventory.setItem(5, createItemStack(teamColor + "Gebiets-Manager", Material.COMPARATOR));
        if (team.getMembersList().size() > 1) inventory.setItem(6, createItemStack(teamColor + "Rollen", Material.GOLDEN_HELMET));
        if (!isOwner) inventory.setItem(7, createItemStack(teamColor + "§4Team verlassen", Material.DARK_OAK_DOOR));
        inventory.setItem(8, createItemStack(teamColor + "§cBye", Material.BARRIER));
        return inventory;
    }


    private static Inventory createAreaSettingsInventory(String teamColor, TeamCacheObject team) {
        Inventory inventory = Menu.create(Menu.Type.TEAM_AREA, 9, teamColor + "§nGebiets-Manager");
        String on = "§a§lAN";
        String off = "§c§lAUS";
        String pvp = team.isZoneOptionPvP() ? on : off;
        String mobGriefing = team.isZoneOptionMobDamage() ? on : off;
        String interaction = team.isZoneOptionInteract() ? on : off;

        int teamLevel = team.getLevel();

        ItemStack grayPane = createItemStack("§8---", Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, grayPane);
        }
        inventory.setItem(2, createItemStack("§fMobGriefing §8- " + mobGriefing, teamLevel>=2 ? Material.CREEPER_HEAD : Material.BARRIER));
        inventory.setItem(4, createItemStack("§fPVP §8- " + pvp, teamLevel>=3 ? Material.GOLDEN_SWORD : Material.BARRIER));
        inventory.setItem(6, createItemStack("§fInteraktionen §8- " + interaction, teamLevel>=5 ? Material.LEVER : Material.BARRIER));
        inventory.setItem(8, createItemStack("§cZurück", Material.OAK_DOOR));
        return inventory;
    }

    private static @NotNull ItemStack getTeamUpgradeItem(String teamColor, TeamCacheObject team) {
        int currentLevel = team.getLevel();
        int upgradeCost = currentLevel==1 ? 5000 : (currentLevel * Main.getTeamLevelMultiple());
        List<String> lore = new ArrayList<>();
        lore.add("§fAktuelles Level: " + teamColor + currentLevel);

        String itemName;
        if (currentLevel >= 5) {
            itemName = teamColor + "§lMax-Level Team";
        }else {
            itemName = teamColor + "§lTeam-Upgrade";
            lore.add("");
            lore.add("§fUpgrade für " + teamColor + upgradeCost + " §fTeam-Punkte");
        }
        return createItemStack(itemName, Material.NETHER_STAR, lore.toArray(new String[0]));
    }


    private static Inventory createTeamInvitesInventory(String teamColor) {
        Inventory inventory = Menu.create(Menu.Type.TEAM_INVITE, 18, teamColor + "§nNeues Mitglied");
        inventory.setItem(0, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(8, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(9, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(17, createItemStack("§cZurück", Material.BARRIER));

        int itemSlot = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerCacheObject tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            if (tmpPlayerCacheObject.isTeamInvites() && tmpPlayerCacheObject.getTeamID() == null) {
                itemSlot++;
                if (itemSlot>15)break;
                if (itemSlot==8) itemSlot = 10;

                ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();
                playerHeadMeta.setOwningPlayer(player);
                playerHeadMeta.customName(Text.of("§a" + player.getName()));
                playerHead.setItemMeta(playerHeadMeta);
                inventory.setItem(itemSlot, playerHead);
            }
        }
        return inventory;
    }


    private static Inventory createTeamRoleInventory(String teamColor, TeamCacheObject teamCacheObject) {
        Inventory inventory = Menu.create(Menu.Type.TEAM_ROLES, 18, teamColor + "§nTeam-Rollen");

        inventory.setItem(0, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(8, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(9, createItemStack("§7---", Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(17, createItemStack("§cZurück", Material.BARRIER));

        List<Document> memberList = teamCacheObject.getMembersList();
        List<String> viceList = teamCacheObject.getTeamVices();
        List<String> lores = List.of(
                "§fSteuerung (Maus):",
                "§aLinks  §8- §fRolle verändern",
                "§cRechts §8- §fSpieler kicken"
        );

        int itemSlot = 0;
        for (int i = 0; i < memberList.size(); i++) {
            OfflinePlayer teamMember = Bukkit.getOfflinePlayer(UUID.fromString(memberList.get(i).getString("uuid")));
            if (teamCacheObject.getTeamOwner().equals(teamMember.getUniqueId().toString())) continue;
            itemSlot++;
            if (i == 8) itemSlot = 10;
            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta playerHeadMeta = (SkullMeta) playerHead.getItemMeta();
            playerHeadMeta.setOwningPlayer(teamMember);
            String role = viceList.contains(teamMember.getUniqueId().toString()) ? String.format("§f(%s§oVize§f) ", teamColor) : "§f";
            playerHeadMeta.customName(Text.of(role + teamMember.getName()));
            playerHeadMeta.lore(Text.lore(lores));
            playerHead.setItemMeta(playerHeadMeta);
            inventory.setItem(itemSlot, playerHead);
        }
        return inventory;
    }


    private static ItemStack getColoredBanner(@NotNull String teamColor) {
        Material material = switch (teamColor) {
            case "§0" -> Material.BLACK_BANNER;
            case "§1" -> Material.BLUE_BANNER;
            case "§2" -> Material.GREEN_BANNER;
            case "§3" -> Material.CYAN_BANNER;
            case "§4" -> Material.RED_BANNER;
            case "§5" -> Material.PURPLE_BANNER;
            case "§6" -> Material.BROWN_BANNER;
            case "§7" -> Material.LIGHT_GRAY_BANNER;
            case "§8" -> Material.GRAY_BANNER;
            case "§9" -> Material.BLUE_BANNER;
            case "§a" -> Material.LIME_BANNER;
            case "§b" -> Material.LIGHT_BLUE_BANNER;
            case "§c" -> Material.RED_BANNER;
            case "§d" -> Material.MAGENTA_BANNER;
            case "§e" -> Material.YELLOW_BANNER;
            default -> Material.WHITE_BANNER;
        };
        return createItemStack(teamColor + "Aktuellen Chunk beanspruchen §f(§c-200 Team-Punkte§f)", material);
    }


}
