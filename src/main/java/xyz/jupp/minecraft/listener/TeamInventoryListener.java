package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.*;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.database.TeamBlockCollection;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.inventory.TeamInventory;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.TeamBlock;

public class TeamInventoryListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        @NotNull InventoryView inventory = event.getView();
        String title = inventory.getOriginalTitle();
        HumanEntity entity = event.getWhoClicked();
        if (entity instanceof Player) {
            Player player = (Player) entity;

            if (title.equals("§aTeam erstellen")) {
                event.setCancelled(true);
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null) return;
                String displayName = clickedItem.getItemMeta().getDisplayName();

                if (displayName.equals("§a§lTeam gründen")) {
                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                        ItemStack teamNamePaperItem = inventory.getItem(0);
                        String teamColor = teamNamePaperItem.getItemMeta().getDisplayName().substring(0, 2);
                        if (teamColor.equals("§a")) {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            return;
                        }
                        String teamName = teamNamePaperItem.getItemMeta().getDisplayName().substring(2);
                        CacheHandler.getInstance().createNewTeam(player, teamName, teamColor);
                        PlayerCollection playerCollection = new PlayerCollection(player);
                        int money = playerCollection.getMoney();
                        playerCollection.updateMoney(money - 500);
                        player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 2f);
                        player.sendMessage(Main.getChatPrefix() + String.format("Du hast das Team §%s%s §ferstellt!", teamColor, teamName));
                        player.setPlayerListName("§" + teamColor + "§l" + player.getName());
                        player.setDisplayName("§" + teamColor + "§l" + player.getName());
                    });
                    player.closeInventory();
                }

                Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                    if (!clickedItem.getType().equals(Material.PAPER)
                            || !clickedItem.getType().equals(Material.NETHER_STAR)
                            || !clickedItem.getType().equals(Material.GRAY_STAINED_GLASS_PANE)) {
                        changeSelectedColor(inventory, displayName.substring(0,2), player);
                    }
                });
                return;
            }


            if (title.contains("§nTeam-Menü")) {
                event.setCancelled(true);

                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null) return;
                String displayName = clickedItem.getItemMeta().getDisplayName();

                if (displayName.contains("Mitglied hinzufügen")) {
                    PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                    TeamInventory.openInventory(TeamInventory.TeamInventoryTypes.INVITE, playerCacheObject);;
                    return;
                }

                if (displayName.contains("TeamBlock")) {
                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                        TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
                        int alreadyPurchased = teamCacheObject.getAlreadyPurchased();
                        int price = 2500 + (alreadyPurchased * 2500);
                        int money = playerCacheObject.getPlayerCollection().getMoney();

                        if (money < price) {
                            player.sendMessage(Main.getChatPrefix() + "Du hast derzeit nicht genügend Taler.");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            return;
                        }

                        playerCacheObject.getPlayerCollection().updateMoney(money - price);
                        CacheHandler.getInstance().incAlreadyPurchased(playerCacheObject);
                        player.getInventory().addItem(TeamBlock.createTeamBlock(playerCacheObject));
                        TeamBlockCollection teamBlockCollection = new TeamBlockCollection(playerCacheObject.getTeamID());

                        teamBlockCollection.createNewTeamBlock();
                        TeamBlockCacheObject teamBlockCacheObject = TeamBlockCache.getTeamBlock(playerCacheObject.getTeamID());
                        teamBlockCacheObject.setActive(true);

                        Logger.console("teamblock (" +alreadyPurchased + ") was bought [" + teamCacheObject.getTeamID() + "]");

                        PlayerCacheObject tmpPlayerCacheObject = null;
                        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                            onlinePlayer.sendMessage(Main.getChatPrefix() + "Der TeamBlock von " + playerCacheObject.getTeamColor()+playerCacheObject.getTeamCacheObject().getTeamName() + " §fist nun aktiv!");
                            tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(onlinePlayer);
                            if ((tmpPlayerCacheObject.getTeamID() != null) && (tmpPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) ){
                                onlinePlayer.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f,2f);
                                onlinePlayer.sendMessage(playerCacheObject.getTeamColor() + "Team Info §8» §fEuer TeamBlock wurde " + (alreadyPurchased>0 ? "wieder " : "") + "aktiviert!");
                                onlinePlayer.sendMessage(playerCacheObject.getTeamColor() + "§fNun habt ihr die Möglichkeit durch seltene Items, TeamPunkte freizuschalten. Diese bieten euch starke Extras!");
                                onlinePlayer.sendMessage(playerCacheObject.getTeamColor() + "§fDer Block kann jedoch von anderen Teams zerstört werden, passt also gut auf ihn auf.");
                                continue;
                            }
                            onlinePlayer.playSound(onlinePlayer.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 2f,2f);
                        }
                    });
                    player.closeInventory();
                    return;
                }

                if (displayName.contains("Rollen")) {
                    PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                    TeamInventory.openInventory(TeamInventory.TeamInventoryTypes.SETTINGS, playerCacheObject);;
                    return;
                }

                if (displayName.equals("§4Team verlassen")) {
                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                        Bukkit.getScheduler().runTask(Main.getInstance(), ()->player.closeInventory());
                        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                        String teamName = playerCacheObject.getTeamCacheObject().getTeamName();
                        String teamColor = playerCacheObject.getTeamColor();
                        String teamID = playerCacheObject.getTeamID();
                        CacheHandler.getInstance().removePlayerFromTeam(player, playerCacheObject.getTeamCacheObject());

                        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 2f,2f);
                        player.sendMessage(Main.getChatPrefix() + "§fDu hast das Team " + teamColor + teamName + " §fverlassen.");
                        player.setPlayerListName("§a"+player.getName());

                        PlayerCacheObject tmpPlayerCacheObject = null;
                        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                            tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(onlinePlayer);
                            if (tmpPlayerCacheObject.getTeamID() != null && tmpPlayerCacheObject.getTeamID().equals(teamID)){
                                onlinePlayer.playSound(onlinePlayer.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                                onlinePlayer.sendMessage(tmpPlayerCacheObject.getTeamColor() + "Team-Info§8» §a" + player.getName() + " §fhat das Team verlassen.");
                            }
                        }
                    });
                    return;
                }

                if (displayName.contains("§cBye")) {
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 2f,2f);
                    player.closeInventory();
                    return;
                }

                return;
            }


            if (title.contains("§nTeam-Rollen")) {
                event.setCancelled(true);

                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null) return;
                String displayName = clickedItem.getItemMeta().getDisplayName();

                if (displayName.equals("§cZurück")) {
                    player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                    TeamInventory.openInventory(player, TeamInventory.TeamInventoryTypes.MAIN);
                    return;
                }

                if (!displayName.equals("§7---")) {
                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                        String playerName;
                        if (displayName.contains(" ")) {
                            playerName = displayName.split(" ")[1];
                        }else {
                            playerName = displayName.replace("§f", "");
                        }

                        Player selectedPlayer = Bukkit.getOfflinePlayer(playerName).getPlayer();
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);

                        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);

                        if (event.getClick().isLeftClick()) {
                            String newRole = null;
                            if (selectedPlayer.isOnline()) {
                                PlayerCacheObject selectedPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(selectedPlayer);
                                newRole = CacheHandler.getInstance().changeTeamMemberRole(selectedPlayerCacheObject);
                            }else {
                                TeamCollection teamCollection = new TeamCollection(playerCacheObject.getTeamID());
                                newRole = teamCollection.changeRoleFromMember(player);
                            }

                            ItemMeta itemMeta = clickedItem.getItemMeta();
                            if (newRole.equals("vice")) {
                                itemMeta.setDisplayName(String.format("§f(%s§oVize§f) %s", playerCacheObject.getTeamColor(), selectedPlayer.getName()));
                                clickedItem.setItemMeta(itemMeta);
                                player.updateInventory();

                                selectedPlayer.sendMessage(playerCacheObject.getTeamColor() + "Team-Info§8» §fDu bist nun §oVize§f.");
                                selectedPlayer.playSound(selectedPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,2f,2f);
                                selectedPlayer.setPlayerListName(playerCacheObject.getTeamColor() + "§o" + selectedPlayer.getName());

                            }else {
                                itemMeta.setDisplayName(String.format("§f%s", selectedPlayer.getName()));
                                clickedItem.setItemMeta(itemMeta);
                                player.updateInventory();

                                selectedPlayer.sendMessage(playerCacheObject.getTeamColor() + "Team-Info§8» §fDu bist nun Mitglied§f.");
                                selectedPlayer.playSound(selectedPlayer.getLocation(), Sound.BLOCK_LAVA_POP,2f,2f);
                                selectedPlayer.setPlayerListName(playerCacheObject.getTeamColor() + "§o" + selectedPlayer.getName());

                            }
                            return;
                        }

                        if (event.getClick().isRightClick()) {
                            Bukkit.getScheduler().runTask(Main.getInstance(), () -> player.closeInventory());
                            String teamID = playerCacheObject.getTeamID();
                            CacheHandler.getInstance().removePlayerFromTeam(selectedPlayer, playerCacheObject.getTeamCacheObject());

                            selectedPlayer.playSound(selectedPlayer.getLocation(), Sound.ENTITY_PLAYER_DEATH, 2f,2f);
                            selectedPlayer.sendMessage(Main.getChatPrefix() + "Du wurdest aus deinem Team entfernt.");
                            selectedPlayer.setPlayerListName("§a" + selectedPlayer.getName());


                            PlayerCacheObject tmpPlayerCacheObject = null;
                            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                                tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(onlinePlayer);
                                if (tmpPlayerCacheObject.getTeamID() != null && tmpPlayerCacheObject.getTeamID().equals(teamID)) {
                                    onlinePlayer.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                                    onlinePlayer.sendMessage(Main.getChatPrefix() + "Das Mitglied §a" + selectedPlayer.getName() + " §fist nun nicht mehr im Team.");
                                }
                            }
                        }

                    });


                }

                return;
            }


            if (title.contains("§nNeues Mitglied")) {
                event.setCancelled(true);

                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null) return;
                String displayName = clickedItem.getItemMeta().getDisplayName();

                if (displayName.equals("§cZurück")) {
                    player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                    TeamInventory.openInventory(player, TeamInventory.TeamInventoryTypes.MAIN);
                    return;
                }

                if (!displayName.equals("§7---")) {
                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                        Player selectedPlayer = Bukkit.getOfflinePlayer(displayName.replace("§a", "")).getPlayer();

                        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                        TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
                        CacheHandler.getInstance().addPlayerToTeam(selectedPlayer, teamCacheObject);

                        selectedPlayer.setPlayerListName(playerCacheObject.getTeamColor() + selectedPlayer.getName());
                        selectedPlayer.playSound(selectedPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
                        selectedPlayer.sendMessage(Main.getChatPrefix() + "Du bist " + playerCacheObject.getTeamColor() + teamCacheObject.getTeamName() + " §fbeigetreten.");

                        PlayerCacheObject tempPlayerCacheObject = null;
                        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                            tempPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(onlinePlayer);
                            if (tempPlayerCacheObject.getTeamID() == null) continue;
                            if (tempPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                                onlinePlayer.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 2f,2f);
                                onlinePlayer.sendMessage(playerCacheObject.getTeamColor() + "Team-Info§8» §fWir haben ein neues Mitglied!");
                                onlinePlayer.sendMessage(playerCacheObject.getTeamColor() + "Team-Info§8» §a" + selectedPlayer.getName() + " §fist nun in unserem Team.");
                            }
                        }
                    });
                    player.closeInventory();
                }

            }

        }
    }


    private void changeSelectedColor(InventoryView inventoryView, String color, Player player) {
        ItemStack teamNamePaperItem = inventoryView.getItem(0);
        ItemMeta itemMeta = teamNamePaperItem.getItemMeta();
        String teamName = itemMeta.getDisplayName().substring(2);
        itemMeta.setDisplayName(color + teamName);
        teamNamePaperItem.setItemMeta(itemMeta);
        player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
        player.updateInventory();
    }
}
