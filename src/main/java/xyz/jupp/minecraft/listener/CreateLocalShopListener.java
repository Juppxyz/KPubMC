package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.regex.Pattern;

public class CreateLocalShopListener implements Listener {

    private static final String SHOP_TITLE = "§6Shop von";
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile(" Stk.");
    private static final Pattern PRICE_SUFFIX = Pattern.compile(" S.");

    @EventHandler
    public void onShopChestShop(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        // the block types with a chest state, checked without creating a block state
        Material clickedType = clicked.getType();
        if (clickedType != Material.CHEST && clickedType != Material.TRAPPED_CHEST) return;
        Player player = event.getPlayer();
        BlockFace facing = ((org.bukkit.block.data.type.Chest) clicked.getBlockData()).getFacing();

        if (clicked.getRelative(facing).getState(false) instanceof Sign sign && SHOP_TITLE.equals(line(sign, 0))) {
            if (!player.getName().equals(line(sign, 1).replace("§6", ""))) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                event.setCancelled(true);
            }
        }
    }


    @EventHandler
    public void onLocalSignInteract(PlayerInteractEvent event) {
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || !(clickedBlock.getState(false) instanceof Sign sign)) return;

        String firstLine = line(sign, 0);
        if (!SHOP_TITLE.equals(firstLine)) return;

        Player player = event.getPlayer();
        String secondLine = line(sign, 1);
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

        Block attachedChest = null;
        for (BlockFace face : BlockFace.values()) {
            Block adjacentBlock = clickedBlock.getRelative(face);
            if (adjacentBlock.getType() == Material.CHEST) {
                attachedChest = adjacentBlock;
                break;
            }
        }

        if (attachedChest == null || !(attachedChest.getState(false) instanceof Chest chest)) return;

        if (chest.getInventory().isEmpty()) {
            player.sendMessage(Main.getChatPrefix() + "§fDer Shop von §6" + secondLine + " §fist aktuell leer.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }

        buy(player, sign, secondLine, attachedChest);
    }


    // the sign is read here, the checks keep their former order on the worker (balance first)
    private static void buy(Player player, Sign sign, String secondLine, Block chestBlock) {
        String[] splitLastLine = line(sign, 3).split(" §8- ");
        boolean malformed = splitLastLine.length < 2;
        int amount = malformed ? 0 : verifyNumbers(AMOUNT_SUFFIX.matcher(splitLastLine[0]).replaceAll("").replace("§a", ""));
        int sellPrice = malformed ? 0 : verifyNumbers(PRICE_SUFFIX.matcher(splitLastLine[1]).replaceAll("").replace("§a", ""));
        Material shopItem = Material.getMaterial(line(sign, 2).replace("§a", ""));
        String ownerName = secondLine.replace("§6", "");

        Tasks.async(() -> {
            int currentMoney = PlayerRepository.getMoney(player);

            if (currentMoney <= 0) {
                notifyBuyer(player, "§cDein Konto ist derzeit leider leer.");
                return;
            }

            // a last line without price separator: nothing happened before either (exception)
            if (malformed) return;

            if (sellPrice == 0) {
                notifyBuyer(player, "§cUps, ein unerwarteter Fehler ist aufgetreten. (-197)");
                return;
            }

            if (amount == 0) {
                notifyBuyer(player, "§cUps, ein unerwarteter Fehler ist aufgetreten. (-198)");
                return;
            }

            int updatedMoney = currentMoney - sellPrice;
            if (sellPrice > currentMoney || updatedMoney < 0) {
                notifyBuyer(player, "§cDein Konto ist aktuell leider nicht ausreichend gedeckt.");
                return;
            }

            // possibly blocking name lookup, Bukkit rejects a blank name (nothing happened before either)
            OfflinePlayer offlinePlayer;
            try {
                offlinePlayer = Bukkit.getOfflinePlayer(ownerName);
            } catch (IllegalArgumentException e) {
                return;
            }
            if (!offlinePlayer.hasPlayedBefore()) {
                notifyBuyer(player, "§cDer Spieler war leider noch nie auf dem Server.");
                return;
            }

            // pay first, so the goods are only taken out of the chest for a covered purchase
            if (!PlayerRepository.tryWithdrawMoney(player.getUniqueId(), sellPrice)) {
                notifyBuyer(player, "§cDein Konto ist aktuell leider nicht ausreichend gedeckt.");
                return;
            }

            MainThread.deliverOrRefund(player.getUniqueId(), sellPrice,
                    () -> handOver(player, chestBlock, shopItem, amount, sellPrice, offlinePlayer, secondLine));
        });
    }

    // main thread: takes the goods out of the chest (looked up again, it may be gone by now), the bookings run async
    private static void handOver(Player player, Block chestBlock, @Nullable Material shopItem, int amount, int sellPrice, OfflinePlayer offlinePlayer, String secondLine) {
        boolean successfullyRemoved = chestBlock.getState(false) instanceof Chest chest && removeItems(chest, shopItem, amount);
        if (!successfullyRemoved) {
            Tasks.async(() -> PlayerRepository.addMoney(player, sellPrice));
            player.sendMessage(Main.getChatPrefix() + "§fDer Shop von §6" + secondLine + " §fist aktuell nicht ausreichend gefüllt.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }

        // by uuid, the shop owner may be offline
        Tasks.async(() -> PlayerRepository.addMoney(offlinePlayer.getUniqueId(), sellPrice));

        player.getInventory().addItem(new ItemStack(shopItem, amount)).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        Logger.console(String.format("%s bought %s(%d) from %s", player.getName(), shopItem.name(), amount, offlinePlayer.getName()));

        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
        player.sendMessage(Main.getChatPrefix() + "Du hast §6" + amount + " " + shopItem.name() + " §fvon §a" + offlinePlayer.getName() + " §ferworben.");

        if (offlinePlayer.isOnline()) {
            offlinePlayer.getPlayer().playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
            offlinePlayer.getPlayer().sendMessage(Main.getChatPrefix() + "§a" + player.getName() + " §fhat aus deinem Shop §6"+ amount + " " + shopItem.name() + " §fgekauft.");
        }
    }

    private static void notifyBuyer(Player player, String message) {
        MainThread.run(() -> {
            player.sendMessage(Main.getChatPrefix() + message);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        });
    }

    @EventHandler
    public void onLocalShopSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        String shopPrefixTitle = Text.section(event.line(0));
        if (shopPrefixTitle == null) return;

        if (SHOP_TITLE.equalsIgnoreCase(shopPrefixTitle) && !("§6" + player.getName()).equals(Text.section(event.line(1)))) {
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

        int sellPrice = verifyNumbers(Text.section(event.line(1)));
        int amount = verifyNumbers(Text.section(event.line(2)));

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

        if (!(signBlock.getBlockData() instanceof WallSign wallSign)) {
            event.getPlayer().sendMessage(Main.getChatPrefix() + "§cBitte platziere das Shop-Schild an der Vorderseite einer Truhe.");
            event.setCancelled(true);
            return;
        }

        BlockFace attachedFace = wallSign.getFacing().getOppositeFace();

        Block attachedBlock = signBlock.getRelative(attachedFace);
        if (attachedBlock.getType() != Material.CHEST) {
            event.getPlayer().sendMessage(Main.getChatPrefix() + "§cBitte platziere das Shop-Schild an der Vorderseite einer Truhe.");
            event.setCancelled(true);
            return;
        }

        if (!(attachedBlock.getState() instanceof Chest chest)) {
            player.sendMessage(Main.getChatPrefix() + "§cBitte platziere das Shop-Schild an der Vorderseite einer normalen Truhe.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            return;
        }

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

        event.line(0, Text.section(SHOP_TITLE));
        event.line(1, Text.section("§6" + player.getName()));
        event.line(2, Text.section("§a"+ chestItem.name()));
        char firstLetterOfCurrency = Main.getCurrencyName().charAt(0);
        event.line(3, Text.section(String.format("§a%d Stk. §8- §a%d %s.", amount, sellPrice, firstLetterOfCurrency)));

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 2f,2f);
    }

    // sign lines are compared as the legacy text of the front side
    private static String line(Sign sign, int index) {
        return Text.section(sign.getSide(Side.FRONT).line(index));
    }

    private static int verifyNumbers(String rawNumber) {
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

    // main thread only: false (and nothing removed) if the chest has too few; before, the rest was taken and lost
    private static boolean removeItems(Chest chest, @Nullable Material material, int amountToRemove) {
        Inventory inventory = chest.getInventory();
        ItemStack[] contents = inventory.getContents();
        int available = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() == material) available += item.getAmount();
        }
        if (material == null || available < amountToRemove) return false;
        int remainingAmount = amountToRemove;
        for (ItemStack item : contents) {
            if (remainingAmount <= 0) break;
            if (item == null || item.getType() != material) continue;
            int part = Math.min(remainingAmount, item.getAmount());
            item.setAmount(item.getAmount() - part);
            remainingAmount -= part;
        }
        inventory.setContents(contents);
        return true;
    }

}
