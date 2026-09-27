package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.database.TeamRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.inventory.Menu;
import xyz.jupp.minecraft.inventory.TeamInventory;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.EnumSet;
import java.util.Set;

public class TeamInventoryListener implements Listener {

    // same price that /team neu announces and checks
    public static final int TEAM_CREATION_COST = 2500;

    private static final Set<Menu.Type> TEAM_MENUS = EnumSet.of(
            Menu.Type.TEAM_CREATE, Menu.Type.TEAM_MAIN, Menu.Type.TEAM_ROLES, Menu.Type.TEAM_INVITE, Menu.Type.TEAM_AREA);

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Menu menu = Menu.of(event.getInventory());
        if (menu == null || !TEAM_MENUS.contains(menu.getType())) return;
        event.setCancelled(true);
        // only the menu's own buttons count, not (renamed) items in the player's inventory
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null) return;
        ItemMeta clickedMeta = clickedItem.getItemMeta();
        // empty slot: nothing happened before either (exception)
        if (clickedMeta == null) return;
        String displayName = Text.legacy(clickedMeta.customName());

        switch (menu.getType()) {
            case TEAM_CREATE -> onCreateClick(player, event.getInventory(), displayName);
            case TEAM_MAIN -> onMainClick(player, event, displayName);
            case TEAM_ROLES -> onRolesClick(player, event, clickedItem, displayName);
            case TEAM_INVITE -> onInviteClick(player, displayName);
            case TEAM_AREA -> onAreaClick(player, displayName);
            default -> {}
        }
    }


    private static void onCreateClick(Player player, Inventory inventory, String displayName) {
        if (displayName.equals("§a§lTeam gründen")) {
            // read before the colour is reset below, so the team gets the selected colour
            String teamNameWithColor = Text.legacy(inventory.getItem(0).getItemMeta().customName());
            String teamColor = teamNameWithColor.substring(0, 2);
            if (teamColor.equals("§a")) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            } else {
                String teamName = teamNameWithColor.substring(2);
                Tasks.async(() -> {
                    if (!PlayerRepository.tryWithdrawMoney(player, TEAM_CREATION_COST)) {
                        MainThread.run(() -> {
                            player.sendMessage(Main.getChatPrefix() + "Das gründen eines Teams kostet " + Main.getCurrencyName(TEAM_CREATION_COST) + "§f.");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                        });
                        return;
                    }
                    CacheHandler.getInstance().createNewTeam(player, teamName, teamColor);
                    MainThread.run(() -> {
                        player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 2f);
                        player.sendMessage(Main.getChatPrefix() + String.format("Du hast das Team %s%s §ferstellt!", teamColor, teamName));
                        player.playerListName(Text.listName(teamColor + "§l" + player.getName(), player.getName()));
                        player.setDisplayName(teamColor + "§l" + player.getName());
                    });
                });
            }
            player.closeInventory();
        }

        // every click (also "Team gründen" and items in the own inventory) takes the colour code of the clicked name
        if (displayName.length() < 2) return;
        changeSelectedColor(inventory, displayName.substring(0, 2), player);
    }


    private static void onMainClick(Player player, InventoryClickEvent event, String displayName) {
        if (displayName.contains("Mitglied hinzufügen")) {
            if (Bukkit.getServer().getOnlinePlayers().size() < 2) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return;
            }

            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            TeamInventory.openInventory(TeamInventory.TeamInventoryTypes.INVITE, playerCacheObject);
            return;
        }

        if (displayName.contains("Team-Upgrade")) {
            upgradeTeam(player);
        }

        if (displayName.contains("Rollen")) {
            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            TeamInventory.openInventory(TeamInventory.TeamInventoryTypes.SETTINGS, playerCacheObject);
            return;
        }

        if (displayName.contains("Aktuellen Chunk beanspruchen")) {
            claimChunk(player);
        }

        if (displayName.contains("Gebiets-Manager")) {
            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            TeamInventory.openSettingsInventory(player, playerCacheObject);
            return;
        }

        if (displayName.equals("§4Team verlassen")) {
            leaveTeam(player);
            return;
        }

        if (displayName.contains("§cBye")) {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 2f,2f);
            player.closeInventory();
        }
    }


    private static void upgradeTeam(Player player) {
        Tasks.async(() -> {
            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
            if (teamCacheObject == null) return;
            if (!teamCacheObject.getTeamOwner().contains(player.getUniqueId().toString())) {
                MainThread.run(() -> {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    player.sendMessage(
                            "%s§fNur %sBesitzer und %sVize §fkönnen das Team-Level §fupgraden."
                            .formatted(Main.getChatPrefix(), teamCacheObject.getTeamColor(), teamCacheObject.getTeamColor())
                    );
                });
                return;
            }

            // level, price and withdrawal under the team's lock (same monitor as upgradeTeamLevel),
            // so a double click cannot buy two levels at the price of the current one
            boolean upgraded;
            synchronized (teamCacheObject) {
                int teamLevel = teamCacheObject.getLevel();
                // second click in the still open menu after the max level was reached (the menu shows "Max-Level Team" then)
                if (teamLevel >= 5) return;
                int cost = teamLevel == 1 ? 5000 : (teamLevel * Main.getTeamLevelMultiple());
                upgraded = TeamRepository.tryWithdrawTeamPoints(teamCacheObject.getTeamID(), cost);
                if (upgraded) teamCacheObject.upgradeTeamLevel();
            }

            if (!upgraded) {
                MainThread.run(() -> {
                    player.sendMessage("%s§fDein %sTeam §fhat §cnicht §fgenügend Punkte um das Level zu upgraden.".formatted(Main.getChatPrefix(), teamCacheObject.getTeamColor()));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    player.closeInventory();
                });
                return;
            }

            MainThread.run(() -> {
                player.sendMessage(Main.getChatPrefix() + "§aDu hast das Level deines Teams erfolgreich hochgestuft!");
                player.sendMessage(Main.getChatPrefix() + "§fVorteile und Upgrades kannst du am aktuellen Spawn nachlesen.");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 2f,2f);

                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getUniqueId().equals(player.getUniqueId())) continue;
                    if (isTeamMember(online, playerCacheObject.getTeamID())) {
                        online.sendMessage(Main.getChatPrefix() + "§aDein Team hat nun ein höheres Level!");
                        online.sendMessage(Main.getChatPrefix() + "§fVorteile und Upgrades kannst du am aktuellen Spawn nachlesen.");
                        online.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
                    }
                }

                player.closeInventory();
            });
        });
    }


    private static void claimChunk(Player player) {
        Location currentLocation = player.getLocation();

        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            player.sendMessage(Main.getChatPrefix() + "§cDu kannst keine Chunks im End beanspruchen.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            return;
        }

        if (Locations.isLocationASpawn(currentLocation)) {
            player.sendMessage(Main.getChatPrefix() + "§cDu kannst keinen Spawn-Bereich beanspruchen.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            return;
        }

        // chunk from the block coordinates, the chunk itself is not needed
        String worldName = currentLocation.getWorld().getName();
        int chunkX = currentLocation.getBlockX() >> 4;
        int chunkZ = currentLocation.getBlockZ() >> 4;

        Tasks.async(() -> {
            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            String teamID = playerCacheObject.getTeamID();

            if (ChunkCache.getInstance().getClaim(worldName, chunkX, chunkZ) != null) {
                MainThread.run(() -> {
                    player.sendMessage(Main.getChatPrefix() + "§cDieser Chunk wurde bereits beansprucht.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                });
                return;
            }

            if (!TeamRepository.tryWithdrawTeamPoints(teamID, 200)) {
                MainThread.run(() -> {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                    player.sendMessage(Main.getChatPrefix() + "§cDein Team hat leider noch nicht genügend Punkte.");
                });
                return;
            }

            boolean isChunkClaimed = ChunkCache.getInstance().addChunk(teamID, worldName, chunkX, chunkZ);
            if (!isChunkClaimed){
                // claimed by someone else in the meantime: give the points back
                TeamRepository.addTeamPoints(teamID, 200);
                MainThread.run(() -> {
                    player.sendMessage(Main.getChatPrefix() + "§cDieser Chunk wurde bereits beansprucht.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                });
                return;
            }

            MainThread.run(() -> {
                player.sendMessage(Main.getChatPrefix() + "§aDu hast den aktuellen Chunk, erfolgreich für dein " + playerCacheObject.getTeamColor()+ "Team §abeansprucht!");
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 2f,2f);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 2f,2f);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().equals(player.getName())) continue;
                    if (isTeamMember(online, teamID)) {
                        online.sendMessage(Main.getChatPrefix() + playerCacheObject.getTeamColor() + player.getName() + " §ahat einen neuen Chunk für euer Team beansprucht!");
                        online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 2f,2f);
                    }
                }
            });
        });
    }


    private static void leaveTeam(Player player) {
        Tasks.sync(player::closeInventory);
        Tasks.async(() -> {
            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
            // a team that cannot be loaded could not be left before either (exception)
            if (teamCacheObject == null) return;
            String teamName = teamCacheObject.getTeamName();
            String teamColor = playerCacheObject.getTeamColor();
            String teamID = playerCacheObject.getTeamID();
            CacheHandler.getInstance().removePlayerFromTeam(player, teamCacheObject);

            MainThread.run(() -> {
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 2f,2f);
                player.sendMessage(Main.getChatPrefix() + "§fDu hast das Team " + teamColor + teamName + " §fverlassen.");
                player.playerListName(Text.listName("§a" + player.getName(), player.getName()));

                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    PlayerCacheObject onlineCacheObject = CacheHandler.getInstance().getPlayerInCache(onlinePlayer);
                    if (onlineCacheObject.getTeamID() != null && onlineCacheObject.getTeamID().equals(teamID)) {
                        onlinePlayer.playSound(onlinePlayer.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                        onlinePlayer.sendMessage(onlineCacheObject.getTeamColor() + "Team-Info§8» §a" + player.getName() + " §fhat das Team verlassen.");
                    }
                }
            });
        });
    }


    private static void onRolesClick(Player player, InventoryClickEvent event, ItemStack clickedItem, String displayName) {
        if (displayName.equals("§cZurück")) {
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
            TeamInventory.openInventory(player, TeamInventory.TeamInventoryTypes.MAIN);
            return;
        }
        if (displayName.equals("§7---")) return;

        String playerName = roleHeadPlayerName(displayName);
        if (playerName == null) return;
        boolean leftClick = event.getClick().isLeftClick();
        boolean rightClick = event.getClick().isRightClick();

        Tasks.async(() -> {
            Player selectedPlayer;
            try {
                selectedPlayer = Bukkit.getOfflinePlayer(playerName).getPlayer();
            } catch (IllegalArgumentException e) {
                // blank name, rejected by Bukkit: nothing happened before either
                return;
            }
            MainThread.run(() -> {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                if (rightClick) player.closeInventory();
            });

            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);

            if (leftClick) {
                // an unknown or offline name changed nothing before either (exception)
                if (selectedPlayer == null) return;
                String newRole;
                if (selectedPlayer.isOnline()) {
                    PlayerCacheObject selectedPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(selectedPlayer);
                    if (selectedPlayerCacheObject.getTeamCacheObject() == null) return;
                    newRole = CacheHandler.getInstance().changeTeamMemberRole(selectedPlayerCacheObject);
                }else {
                    newRole = TeamRepository.toggleMemberRole(playerCacheObject.getTeamID(), player.getUniqueId());
                }
                MainThread.run(() -> showNewRole(player, selectedPlayer, clickedItem, playerCacheObject.getTeamColor(), newRole));
                return;
            }

            if (rightClick) {
                TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
                // an unknown or offline name kicked nobody before either (exception)
                if (selectedPlayer == null || teamCacheObject == null) return;
                String teamID = playerCacheObject.getTeamID();
                CacheHandler.getInstance().removePlayerFromTeam(selectedPlayer, teamCacheObject);

                MainThread.run(() -> {
                    selectedPlayer.playSound(selectedPlayer.getLocation(), Sound.ENTITY_PLAYER_DEATH, 2f,2f);
                    selectedPlayer.sendMessage(Main.getChatPrefix() + "Du wurdest aus deinem Team entfernt.");
                    selectedPlayer.playerListName(Text.listName("§a" + selectedPlayer.getName(), selectedPlayer.getName()));

                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        if (isTeamMember(onlinePlayer, teamID)) {
                            onlinePlayer.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                            onlinePlayer.sendMessage(Main.getChatPrefix() + "Das Mitglied §a" + selectedPlayer.getName() + " §fist nun nicht mehr im Team.");
                        }
                    }
                });
            }
        });
    }

    // "§f(<color>§oVize§f) Name" or "§fName", null where the former parsing failed with an exception
    private static @Nullable String roleHeadPlayerName(String displayName) {
        if (!displayName.contains(" ")) return displayName.replace("§f", "");
        String[] parts = displayName.split(" ");
        return parts.length > 1 ? parts[1] : null;
    }

    private static void showNewRole(Player player, Player selectedPlayer, ItemStack clickedItem, String teamColor, String newRole) {
        ItemMeta itemMeta = clickedItem.getItemMeta();
        if (newRole.equals("vice")) {
            itemMeta.customName(Text.of(String.format("§f(%s§oVize§f) %s", teamColor, selectedPlayer.getName())));
            clickedItem.setItemMeta(itemMeta);
            player.updateInventory();

            selectedPlayer.sendMessage(teamColor + "Team-Info§8» §fDu bist nun §oVize§f.");
            selectedPlayer.playSound(selectedPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,2f,2f);
            selectedPlayer.playerListName(Text.listName(teamColor + "§o" + selectedPlayer.getName(), selectedPlayer.getName()));
            return;
        }

        itemMeta.customName(Text.of(String.format("§f%s", selectedPlayer.getName())));
        clickedItem.setItemMeta(itemMeta);
        player.updateInventory();

        selectedPlayer.sendMessage(teamColor + "Team-Info§8» §fDu bist nun Mitglied§f.");
        selectedPlayer.playSound(selectedPlayer.getLocation(), Sound.BLOCK_LAVA_POP,2f,2f);
        selectedPlayer.playerListName(Text.listName(teamColor + "§o" + selectedPlayer.getName(), selectedPlayer.getName()));
    }


    private static void onInviteClick(Player player, String displayName) {
        if (displayName.equals("§cZurück")) {
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
            TeamInventory.openInventory(player, TeamInventory.TeamInventoryTypes.MAIN);
            return;
        }
        if (displayName.equals("§7---")) return;

        String selectedName = displayName.replace("§a", "");
        Tasks.async(() -> {
            Player selectedPlayer = onlinePlayer(selectedName);
            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
            // an unknown or offline name invited nobody before either (exception)
            if (selectedPlayer == null || teamCacheObject == null) return;
            CacheHandler.getInstance().addPlayerToTeam(selectedPlayer, teamCacheObject);

            MainThread.run(() -> {
                selectedPlayer.playerListName(Text.listName(playerCacheObject.getTeamColor() + selectedPlayer.getName(), selectedPlayer.getName()));
                selectedPlayer.playSound(selectedPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
                selectedPlayer.sendMessage(Main.getChatPrefix() + "Du bist " + playerCacheObject.getTeamColor() + teamCacheObject.getTeamName() + " §fbeigetreten.");

                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (isTeamMember(onlinePlayer, playerCacheObject.getTeamID())) {
                        onlinePlayer.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 2f,2f);
                        onlinePlayer.sendMessage(playerCacheObject.getTeamColor() + "Team-Info§8» §fWir haben ein neues Mitglied!");
                        onlinePlayer.sendMessage(playerCacheObject.getTeamColor() + "Team-Info§8» §a" + selectedPlayer.getName() + " §fist nun in unserem Team.");
                    }
                }
            });
        });
        player.closeInventory();
    }


    private static void onAreaClick(Player player, String displayName) {
        if (displayName.equals("§cZurück")) {
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
            TeamInventory.openInventory(player, TeamInventory.TeamInventoryTypes.MAIN);
            return;
        }

        AreaOptionsEnum areaOption;
        int requiredLevel;
        if (displayName.startsWith("§fMobGriefing §8- ")) {
            areaOption = AreaOptionsEnum.MOB_GRIEFING;
            requiredLevel = 2;
        } else if (displayName.startsWith("§fPVP §8- ")) {
            areaOption = AreaOptionsEnum.PVP;
            requiredLevel = 3;
        } else if (displayName.startsWith("§fInteraktionen §8- ")) {
            areaOption = AreaOptionsEnum.INTERACTION;
            requiredLevel = 5;
        } else {
            return;
        }

        Tasks.async(() -> {
            PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
            TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
            // a team that cannot be loaded changed nothing before either (exception)
            if (teamCacheObject == null) return;
            boolean changed = teamCacheObject.getLevel() >= requiredLevel;
            if (changed) CacheHandler.getInstance().changeAreaOptions(teamCacheObject, areaOption);

            MainThread.run(() -> {
                String levelMessage = Main.getChatPrefix() + "§fDein " + teamCacheObject.getTeamColor() + "Team §fmuss Level §a" + requiredLevel + " §fsein, um diese Einstellung nutzen zu können.";
                switch (areaOption) {
                    case MOB_GRIEFING -> {
                        if (changed) {
                            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 2f, 2f);
                        } else {
                            player.sendMessage(levelMessage);
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                        }
                    }
                    case PVP -> {
                        if (changed) {
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 2f, 2f);
                        } else {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                            player.sendMessage(levelMessage);
                        }
                    }
                    case INTERACTION -> {
                        if (changed) {
                            player.playSound(player.getLocation(), Sound.BLOCK_CHERRY_WOOD_TRAPDOOR_OPEN, 2f, 2f);
                        } else {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                            player.sendMessage(levelMessage);
                        }
                    }
                }
                player.closeInventory();
            });
        });
    }


    private static void changeSelectedColor(Inventory inventory, String color, Player player) {
        ItemStack teamNamePaperItem = inventory.getItem(0);
        ItemMeta itemMeta = teamNamePaperItem.getItemMeta();
        String teamName = Text.legacy(itemMeta.customName()).substring(2);
        itemMeta.customName(Text.of(color + teamName));
        teamNamePaperItem.setItemMeta(itemMeta);
        player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
        player.updateInventory();
    }

    // possibly blocking name lookup (worker thread only); null for unknown or offline names and blank names, which Bukkit rejects
    private static @Nullable Player onlinePlayer(String name) {
        try {
            return Bukkit.getOfflinePlayer(name).getPlayer();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // main thread: the online player belongs to the team
    private static boolean isTeamMember(Player online, @Nullable String teamID) {
        String onlineTeamID = CacheHandler.getInstance().getPlayerInCache(online).getTeamID();
        return onlineTeamID != null && onlineTeamID.equals(teamID);
    }
}
