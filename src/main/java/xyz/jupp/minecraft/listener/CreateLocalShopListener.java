package xyz.jupp.minecraft.listener;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import xyz.jupp.minecraft.database.PlayerCollection;
import xyz.jupp.minecraft.utils.Logger;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import xyz.jupp.minecraft.Main;

public class CreateLocalShopListener implements Listener {

    @EventHandler
    public void onShopChestShop(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null || !(clicked.getState() instanceof Chest)) return;
        BlockData blockData = clicked.getBlockData();
        org.bukkit.block.data.type.Chest chestData = (org.bukkit.block.data.type.Chest) blockData;
        BlockFace facing = chestData.getFacing();

        Block frontBlock = clicked.getRelative(facing);
        if (frontBlock.getState() instanceof Sign) {
            Sign sign = (Sign) frontBlock.getState();
            if ("§6Shop von".equals(sign.getLine(0))) {
                if (!player.getName().equals(sign.getLine(1).replace("§6", ""))) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    event.setCancelled(true);
                }
            }
        }
    }


    @EventHandler
    public void onLocalSignInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || !(clickedBlock.getState() instanceof Sign)) return;
        Sign sign = (Sign) clickedBlock.getState();

        String firstLine = sign.getLine(0);
        if (!"§6Shop von".equals(firstLine)) return;

        String secondLine = sign.getLine(1);
        String shieldPlayerName = "§6" + player.getName();

        // basically checks if the user is the local shop owner
        if (shieldPlayerName.equals(secondLine) && event.getAction().isRightClick()) {
            event.setCancelled(true);
            player.sendMessage(Main.getChatPrefix() + "§fPlatziere das Schild bitte neu, um deinen Shop zu bearbeiten.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }

        if (shieldPlayerName.equals(secondLine) && event.getAction().isLeftClick()) return;

        if (!shieldPlayerName.equals(secondLine)) {
            event.setCancelled(true);
        }

        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {

            Block attachedChest = null;
            for (BlockFace face : BlockFace.values()) {
                Block adjacentBlock = clickedBlock.getRelative(face);
                if (adjacentBlock.getType() == Material.CHEST) {
                    attachedChest = adjacentBlock;
                    break;
                }
            }

            if (attachedChest == null || !(attachedChest.getState() instanceof Chest)) return;
            Chest chest = (Chest) attachedChest.getState();

            if (chest.getInventory().isEmpty()) {
                player.sendMessage(Main.getChatPrefix() + "§fDer Shop von §6" + secondLine + " §fist aktuell leer.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                PlayerCollection playerCollection = new PlayerCollection(player);
                int currentMoney = playerCollection.getMoney();

                if (currentMoney <= 0) {
                    player.sendMessage(Main.getChatPrefix() + "§cDein Konto ist derzeit leider leer.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    return;
                }

                String[] splitLastLine = sign.getLine(3).split(" §8- ");
                String rawAmount = splitLastLine[0].replaceAll(" Stk.", "").replace("§a", "");
                String rawSellPrice = splitLastLine[1].replaceAll(" S.", "").replace("§a", "");

                int sellPrice = verifyNumbers(rawSellPrice);
                if (sellPrice == 0) {
                    player.sendMessage(Main.getChatPrefix() + "§cUps, ein unerwarteter Fehler ist aufgetreten. (-197)");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    return;
                }

                int amount = verifyNumbers(rawAmount);
                if (amount == 0) {
                    player.sendMessage(Main.getChatPrefix() + "§cUps, ein unerwarteter Fehler ist aufgetreten. (-198)");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    return;
                }

                int updatedMoney = currentMoney - sellPrice;
                if (sellPrice > currentMoney || updatedMoney < 0) {
                    player.sendMessage(Main.getChatPrefix() + "§cDein Konto ist aktuell leider nicht ausreichend gedeckt.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    return;
                }

                Material shopItem = Material.getMaterial(sign.getLine(2).replace("§a", ""));
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(sign.getLine(1).replace("§6", ""));
                if (offlinePlayer.getUniqueId() == null || !offlinePlayer.hasPlayedBefore()) {
                    player.sendMessage(Main.getChatPrefix() + "§cDer Spieler war leider noch nie auf dem Server.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    return;
                }

                boolean successfullyRemoved = removeItems(chest, shopItem, amount);
                if (!successfullyRemoved) {
                    player.sendMessage(Main.getChatPrefix() + "§fDer Shop von §6" + secondLine + " §fist aktuell nicht ausreichend gefüllt.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    return;
                }

                PlayerCollection targetCollection = new PlayerCollection(offlinePlayer.getPlayer());
                int targetMoney = targetCollection.getMoney();
                targetCollection.updateMoney(targetMoney + sellPrice);

                playerCollection.updateMoney(updatedMoney);
                ItemStack itemStack = new ItemStack(shopItem, amount);
                player.getInventory().addItem(itemStack);
                Logger.console(String.format("%s bought %s(%d) from %s", player.getName(), shopItem.name(), amount, offlinePlayer.getPlayer().getName()));

                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
                player.sendMessage(Main.getChatPrefix() + "Du hast §6" + amount + " " + shopItem.name() + " §fvon §a" + offlinePlayer.getName() + " §ferworben.");

                if (offlinePlayer.isOnline()) {
                    offlinePlayer.getPlayer().playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
                    offlinePlayer.getPlayer().sendMessage(Main.getChatPrefix() + "§a" + player.getName() + " §fhat aus deinem Shop §6"+ amount + " " + shopItem.name() + " §fgekauft.");
                }

            });

        });
    }

    @EventHandler
    public void onLocalShopSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        String shopPrefixTitle = event.getLine(0);
        if (shopPrefixTitle == null) return;

        if ("§6Shop von".equalsIgnoreCase(shopPrefixTitle) && !event.getLine(1).equals("§6" + player.getName())) {
            event.setCancelled(true);
            player.sendMessage(Main.getChatPrefix() + "§cDu kannst keine fremden Shops anpassen.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
            return;
        }

        if (!"[Shop]".equals(shopPrefixTitle)) return;

        if (player.getName().length() > 15) {
            player.sendMessage(Main.getChatPrefix() + "§cDu kannst leider aktuell noch keine Shops machen");
            return;
        }

        int sellPrice = verifyNumbers(event.getLine(1));
        int amount = verifyNumbers(event.getLine(2));

        if (sellPrice <= 0 || sellPrice > 9999) {
            player.sendMessage(Main.getChatPrefix() + "§cDer Verkaufspreis muss zwischen 0 und 9999 §a" + Main.getCurrencyName() + " §cbetragen");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            return;
        }

        if (amount <= 0 || amount > 64) {
            player.sendMessage(Main.getChatPrefix() + "§cDie Mengenangabe muss zwischen 0 und 64 liegen.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            return;
        }

        Block signBlock = event.getBlock();
        Block adjChest = null;

        if (!(signBlock.getBlockData() instanceof WallSign)) {
            event.getPlayer().sendMessage(Main.getChatPrefix() + "§cBitte platziere das Shop-Schild an der Vorderseite einer Truhe.");
            event.setCancelled(true);
            return;
        }

        WallSign wallSign = (WallSign) signBlock.getBlockData();
        BlockFace attachedFace = wallSign.getFacing().getOppositeFace();

        Block attachedBlock = signBlock.getRelative(attachedFace);
        if (attachedBlock.getType() != Material.CHEST) {
            event.getPlayer().sendMessage(Main.getChatPrefix() + "§cBitte platziere das Shop-Schild an der Vorderseite einer Truhe.");
            event.setCancelled(true);
            return;
        }

        adjChest = attachedBlock;

        if (adjChest == null || !(adjChest.getState() instanceof Chest)) {
            player.sendMessage(Main.getChatPrefix() + "§cBitte platziere das Shop-Schild an der Vorderseite einer normalen Truhe.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            return;
        }

        Chest chest = (Chest) adjChest.getState();
        Material chestItem = hasOnlyOneMaterialType(chest) ;
        if (chestItem == null) {
            player.sendMessage(Main.getChatPrefix() + "§cDer Shop konnte nicht erstellt werden!");
            player.sendMessage("§8» §fIn der Truhe muss mindestens ein Item sein.");
            player.sendMessage("§8» §fIn der Truhe dürfen nicht verschiedene Item-Typen sein.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            return;
        }

        if (chestItem.name().length() > 15) {
            player.sendMessage(Main.getChatPrefix() + "§cDieses Item kann aktuell noch nicht verwendet werden.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            return;
        }

        event.setLine(0, "§6Shop von");
        event.setLine(1, "§6" + player.getName());
        event.setLine(2, "§a"+ chestItem.name());
        String firstLetterOfCurrency = String.valueOf(Main.getCurrencyName().toCharArray()[0]);
        event.setLine(3, String.format("§a%d Stk. §8- §a%d %s.", amount, sellPrice, firstLetterOfCurrency ));

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 2f,2f);
    }

    private int verifyNumbers(String rawNumber) {
        if (rawNumber == null) return 0;
        try {
            return Integer.parseInt(rawNumber);
        }catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Material hasOnlyOneMaterialType(Chest chest) {
        Inventory inventory = chest.getInventory();
        Material firstMaterial = null;
        for (ItemStack item : inventory.getContents()) {
            if (item != null) {
                if (firstMaterial == null) {
                    firstMaterial = item.getType();
                } else if (item.getType() != firstMaterial) {
                    return null;
                }
            }
        }
        return firstMaterial;
    }

    private static boolean removeItems(Chest chest, Material material, int amountToRemove) {
        Inventory inventory = chest.getInventory();
        int remainingAmount = amountToRemove;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) {
                int itemAmount = item.getAmount();
                if (itemAmount <= remainingAmount) {
                    remainingAmount -= itemAmount;
                    inventory.remove(item);
                } else {
                    item.setAmount(itemAmount - remainingAmount);
                    remainingAmount = 0;
                }
                if (remainingAmount <= 0) {
                    return true;
                }
            }
        }
        return remainingAmount <= 0;
    }



}




