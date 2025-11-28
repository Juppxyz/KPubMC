package xyz.jupp.minecraft.listener;

import org.bukkit.*;
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
import xyz.jupp.minecraft.config.ConfigManager;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.database.TeamBlockCollection;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.inventory.TeamInventory;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;
import xyz.jupp.minecraft.utils.Locations;
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
                    if (Bukkit.getServer().getOnlinePlayers().size() < 2) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                        return;
                    }

                    PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                    TeamInventory.openInventory(TeamInventory.TeamInventoryTypes.INVITE, playerCacheObject);;
                    return;
                }

                if (displayName.contains("Team-Upgrade")) {
                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {

                        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                        TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();
                        if (teamCacheObject == null) return;
                        if (!teamCacheObject.getTeamOwner().contains(player.getUniqueId().toString())) {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            player.sendMessage(
                                    "%s§fNur %sBesitzer und %sVize §fkönnen das Team-Level §fupgraden."
                                    .formatted(Main.getChatPrefix(), teamCacheObject.getTeamColor(), teamCacheObject.getTeamColor())
                            );
                            return;
                        }

                        int teamLevel = teamCacheObject.getLevel();
                        int teamPoints = teamCacheObject.getTeamCollection().getTeamPoints();
                        int cost = teamLevel == 1 ? 5000 : (teamLevel * Main.getTeamLevelMultiple());

                        if (teamPoints < cost) {
                            player.sendMessage("%s§fDein %sTeam §fhat §cnicht §fgenügend Punkte um das Level zu upgraden.".formatted(Main.getChatPrefix(), teamCacheObject.getTeamColor()));
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {player.closeInventory();});
                            return;
                        }

                        teamCacheObject.upgradeTeamLevel(teamPoints - cost);
                        player.sendMessage(Main.getChatPrefix() + "§aDu hast das Level deines Teams erfolgreich hochgestuft!");
                        player.sendMessage(Main.getChatPrefix() + "§fVorteile und Upgrades kannst du am aktuellen Spawn nachlesen.");
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f,2f);
                        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 2f,2f);

                        for (Player online : Bukkit.getOnlinePlayers()) {
                            if (online.getUniqueId().toString().equals(player.getUniqueId().toString())) continue;
                            PlayerCacheObject onlinePlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(online);
                            if (onlinePlayerCacheObject.getTeamID() == null) continue;

                            if ( onlinePlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID()) ) {
                                online.sendMessage(Main.getChatPrefix() + "§aDein Team hat nun ein höheres Level!");
                                online.sendMessage(Main.getChatPrefix() + "§fVorteile und Upgrades kannst du am aktuellen Spawn nachlesen.");
                                online.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
                            }
                        }

                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                           player.closeInventory();
                        });

                    });
                }

                /** @Deprecated
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
                }**/

                if (displayName.contains("Rollen")) {
                    PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                    TeamInventory.openInventory(TeamInventory.TeamInventoryTypes.SETTINGS, playerCacheObject);;
                    return;
                }

                if (displayName.contains("Team-Punkte kaufen")) {
                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                        PlayerCollection playerCollection = playerCacheObject.getPlayerCollection();
                        int playerMoney = playerCollection.getMoney();
                        // Klickt der User mit Links -> 100, Rechts -> 1000
                        int tradeType = event.getClick().isLeftClick() ? 1000 : 10000;

                        if (playerMoney < tradeType) {
                            player.sendMessage(Main.getChatPrefix() + "§fDu musst mindestens §c" + tradeType + " " + Main.getCurrencyName() + " §fbesitzen um diese §fzu tauschen." );
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            return;
                        }

                        int tradedMoney = (int) Math.floor(tradeType - (ConfigManager.getManager().getTradeTax() * tradeType) );
                        TeamCollection teamCollection = new TeamCollection(playerCacheObject.getTeamID());
                        int teamPoints = teamCollection.getTeamPoints();
                        teamCollection.changeTeamPoints(teamPoints + tradedMoney);

                        player.sendMessage(Main.getChatPrefix() + "§fDu hast §f" + Main.getCurrencyName(tradedMoney) + " §fin die Team-Kasse eingezahlt!");
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 2f,2f);
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);

                        for (Player online : Bukkit.getOnlinePlayers()) {
                            if (online.getName().equals(player.getName())) continue;
                            PlayerCacheObject tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(online);
                            if (tmpPlayerCacheObject.getTeamID() != null && tmpPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                                online.sendMessage(Main.getChatPrefix() + "§fEs wurden " + Main.getCurrencyName(tradedMoney) + " §fvon " + playerCacheObject.getTeamColor() + player.getName() + " §fin die Team-Kasse eingezahlt!");
                            }
                        }

                    });
                    return;
                }

                if (displayName.contains("Aktuellen Chunk beanspruchen")) {
                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                        Location currentLocation = player.getLocation();

                        if (Locations.isLocationASpawn(currentLocation)) {
                            player.sendMessage(Main.getChatPrefix() + "§fDu kannst keinen Spawn-Bereich beanspruchen.");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            return;
                        }

                        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                        TeamCollection teamCollection = new TeamCollection(playerCacheObject.getTeamID());
                        int teamPoints = teamCollection.getTeamPoints();

                        if (teamPoints < 200) {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            player.sendMessage(Main.getChatPrefix() + "§cDein Team hat leider noch nicht genügend Punkte.");
                            return;
                        }

                        teamCollection.changeTeamPoints(teamPoints - 200);
                        boolean isChunkClaimed = ChunkCache.getInstance().addChunk(
                                playerCacheObject.getTeamID(),
                                currentLocation.getWorld().getName(),
                                currentLocation.getChunk().getX(),
                                currentLocation.getChunk().getZ()
                        );
                        if (!isChunkClaimed){
                            player.sendMessage(Main.getChatPrefix() + "§cDieser Chunk wurde bereits beansprucht.");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                            return;
                        }

                        player.sendMessage(Main.getChatPrefix() + "§aDu hast den aktuellen Chunk, erfolgreich für dein " + playerCacheObject.getTeamColor()+ "Team §abeansprucht!");
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 2f,2f);
                        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 2f,2f);
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            if (online.getName().equals(player.getName())) continue;
                            PlayerCacheObject tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(online);
                            if (tmpPlayerCacheObject.getTeamID() != null && tmpPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                                online.sendMessage(Main.getChatPrefix() + playerCacheObject.getTeamColor() + player.getName() + " §ahat einen neuen Chunk für euer Team beansprucht!");
                                online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 2f,2f);
                            }
                        }

                    });
                }

                if (displayName.contains("Gebiets-Manager")) {
                    PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                    TeamInventory.openSettingsInventory(player, playerCacheObject);;
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


            if (title.contains("§nGebiets-Manager")) {
                event.setCancelled(true);

                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null) return;
                String displayName = clickedItem.getItemMeta().getDisplayName();

                Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                    PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                    TeamCacheObject teamCacheObject = playerCacheObject.getTeamCacheObject();

                    if (displayName.equals("§cZurück")) {
                        player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                        TeamInventory.openInventory(player, TeamInventory.TeamInventoryTypes.MAIN);
                        return;
                    }

                    if (displayName.startsWith("§fMobGriefing §8- ")) {
                        if (teamCacheObject.getLevel() >= 2) {
                            CacheHandler.getInstance().changeAreaOptions(teamCacheObject, AreaOptionsEnum.MOB_GRIEFING);
                            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 2f, 2f);
                        }else {
                            player.sendMessage(Main.getChatPrefix() + "§fDein " + teamCacheObject.getTeamColor() + "Team §fmuss Level §a2 §fsein, um diese Einstellung nutzen zu können.");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                        }
                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {player.closeInventory();});
                    }

                    if (displayName.startsWith("§fPVP §8- ")) {
                        if (teamCacheObject.getLevel() >= 3) {
                            CacheHandler.getInstance().changeAreaOptions(teamCacheObject, AreaOptionsEnum.PVP);
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 2f, 2f);
                        }else {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                            player.sendMessage(Main.getChatPrefix() + "§fDein " + teamCacheObject.getTeamColor() + "Team §fmuss Level §a3 §fsein, um diese Einstellung nutzen zu können.");
                        }
                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {player.closeInventory();});
                    }

                    if (displayName.startsWith("§fInteraktionen §8- ")) {
                        if (teamCacheObject.getLevel() >= 5) {
                            CacheHandler.getInstance().changeAreaOptions(teamCacheObject, AreaOptionsEnum.INTERACTION);
                            player.playSound(player.getLocation(), Sound.BLOCK_CHERRY_WOOD_TRAPDOOR_OPEN, 2f, 2f);
                            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {player.closeInventory();});
                        }else {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                            player.sendMessage(Main.getChatPrefix() + "§fDein " + teamCacheObject.getTeamColor() + "Team §fmuss Level §a5 §fsein, um diese Einstellung nutzen zu können.");
                        }
                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {player.closeInventory();});
                    }

                });
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
