package xyz.jupp.minecraft.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.Loading;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public class CreateLocalShopListener implements Listener {

    private static final String SHOP_TITLE = "§6Shop von";
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile(" Stk.");
    private static final Pattern PRICE_SUFFIX = Pattern.compile(" S.");

    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    // only the owner opens a shop chest: either half of a double chest, whichever side the sign is on
    @EventHandler
    public void onShopChestShop(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null || !isChest(clicked.getType())) return;
        Player player = event.getPlayer();
        // every shop sign on the chest counts: a stranger's own sign hung on it makes nobody else's chest theirs
        if (foreignShop(clicked, player)) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            event.setCancelled(true);
        }
    }

    // a hopper (or a rail for a hopper minecart) under a shop chest would empty it; a chest next to one would join it
    @EventHandler(ignoreCancelled = true)
    public void onPlaceNearShop(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        Material type = placed.getType();
        Block chest;
        if (type == Material.HOPPER || Tag.RAILS.isTagged(type)) {
            chest = placed.getRelative(BlockFace.UP);
            if (!isChest(chest.getType())) return;
        } else if (isChest(type)) {
            chest = otherHalf(placed);
            if (chest == null) return;
        } else {
            return;
        }
        if (!foreignShop(chest, event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        event.getPlayer().sendMessage(Main.getChatPrefix() + "§cDas geht an einem fremden Shop nicht.");
    }


    @EventHandler
    public void onLocalSignInteract(PlayerInteractEvent event) {
        Block clickedBlock = event.getClickedBlock();
        // every click of the server passes here: the material first, a block state only for signs
        if (clickedBlock == null || !Tag.ALL_SIGNS.isTagged(clickedBlock.getType())) return;
        if (!(clickedBlock.getState(false) instanceof Sign sign)) return;

        String firstLine = line(sign, 0);
        if (!SHOP_TITLE.equals(firstLine)) return;

        Player player = event.getPlayer();
        // runs for cancelled clicks too (spawn protection), so a caught prisoner is turned away here
        if (JailHandler.isCaughtPrisoner(player)) return;
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

        // the chest the sign hangs on, nothing else (any chest next to the sign would sell someone else's goods)
        Block attachedChest = attachedChest(clickedBlock);
        if (attachedChest == null || !(attachedChest.getState(false) instanceof Chest chest)) return;

        if (chest.getInventory().isEmpty()) {
            player.sendMessage(Main.getChatPrefix() + "§fDer Shop von §6" + secondLine + " §fist aktuell leer.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }

        // one purchase round per short while: every click asks the database
        if (!Loading.allow(player.getUniqueId())) return;
        buy(player, sign, secondLine, attachedChest);
    }

    private static boolean isChest(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    private static boolean isOwner(Player player, Sign sign) {
        return player.getName().equals(line(sign, 1).replace("§6", ""));
    }

    // the chest behind a wall sign, null for a standing sign or anything else behind it
    private static @Nullable Block attachedChest(Block signBlock) {
        if (!(signBlock.getBlockData() instanceof WallSign wallSign)) return null;
        Block behind = signBlock.getRelative(wallSign.getFacing().getOppositeFace());
        return behind.getType() == Material.CHEST ? behind : null;
    }

    // a shop sign of someone else hangs on this chest or on the other half of its double chest
    private static boolean foreignShop(Block chestBlock, Player player) {
        if (foreignShopSignOn(chestBlock, player)) return true;
        Block other = otherHalf(chestBlock);
        return other != null && foreignShopSignOn(other, player);
    }

    private static boolean foreignShopSignOn(Block chestBlock, Player player) {
        for (BlockFace face : HORIZONTAL) {
            Block block = chestBlock.getRelative(face);
            // a wall sign hanging on this chest faces away from it
            if (!Tag.WALL_SIGNS.isTagged(block.getType()) || !(block.getBlockData() instanceof WallSign wallSign) || wallSign.getFacing() != face) continue;
            if (block.getState(false) instanceof Sign sign && SHOP_TITLE.equals(line(sign, 0)) && !isOwner(player, sign)) return true;
        }
        return false;
    }

    // the connected half of a double chest (as Minecraft finds it: left half -> clockwise of the facing), null for a single one
    private static @Nullable Block otherHalf(Block chestBlock) {
        if (!(chestBlock.getBlockData() instanceof org.bukkit.block.data.type.Chest data)) return null;
        BlockFace facing = data.getFacing();
        return switch (data.getType()) {
            case SINGLE -> null;
            case LEFT -> chestBlock.getRelative(clockwise(facing));
            case RIGHT -> chestBlock.getRelative(clockwise(facing).getOppositeFace());
        };
    }

    private static boolean drainsFromBelow(Block chestBlock) {
        Material below = chestBlock.getRelative(BlockFace.DOWN).getType();
        return below == Material.HOPPER || Tag.RAILS.isTagged(below);
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
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
    // (right away during the shutdown, when this runs from MainThread.runPending and nothing can be scheduled)
    private static void handOver(Player player, Block chestBlock, @Nullable Material shopItem, int amount, int sellPrice, OfflinePlayer offlinePlayer, String secondLine) {
        UUID buyer = player.getUniqueId();
        // left meanwhile: the goods would go into an inventory that is no longer saved
        if (!player.isOnline()) {
            Tasks.asyncOrNow(() -> PlayerRepository.addMoney(buyer, sellPrice));
            return;
        }
        List<ItemStack> goods = chestBlock.getState(false) instanceof Chest chest ? removeItems(chest, shopItem, amount) : null;
        if (goods == null) {
            Tasks.asyncOrNow(() -> PlayerRepository.addMoney(buyer, sellPrice));
            player.sendMessage(Main.getChatPrefix() + "§fDer Shop von §6" + secondLine + " §fist aktuell nicht ausreichend gefüllt.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }

        // by uuid, the shop owner may be offline
        Tasks.asyncOrNow(() -> PlayerRepository.addMoney(offlinePlayer.getUniqueId(), sellPrice));

        // the very stacks from the chest (damage, enchantments and names stay what the owner put in)
        player.getInventory().addItem(goods.toArray(ItemStack[]::new)).values()
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

        // someone else's shop chest (or its other half) cannot become a second shop
        if (foreignShop(attachedBlock, player)) {
            player.sendMessage(Main.getChatPrefix() + "§cAn dieser Truhe hängt schon ein fremder Shop.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            event.setCancelled(true);
            return;
        }

        // a hopper or a hopper minecart's rail under the chest would empty the shop for anyone
        Block otherHalf = otherHalf(attachedBlock);
        if (drainsFromBelow(attachedBlock) || (otherHalf != null && drainsFromBelow(otherHalf))) {
            player.sendMessage(Main.getChatPrefix() + "§cUnter der Shop-Truhe darf kein Trichter und keine Schiene sein.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            event.setCancelled(true);
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

    // main thread only: the removed stacks as they were in the chest; null (and nothing removed) if it has too few
    private static @Nullable List<ItemStack> removeItems(Chest chest, @Nullable Material material, int amountToRemove) {
        Inventory inventory = chest.getInventory();
        ItemStack[] contents = inventory.getContents();
        int available = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() == material) available += item.getAmount();
        }
        if (material == null || available < amountToRemove) return null;
        List<ItemStack> removed = new ArrayList<>();
        int remainingAmount = amountToRemove;
        for (ItemStack item : contents) {
            if (remainingAmount <= 0) break;
            if (item == null || item.getType() != material) continue;
            int part = Math.min(remainingAmount, item.getAmount());
            ItemStack taken = item.clone();
            taken.setAmount(part);
            removed.add(taken);
            item.setAmount(item.getAmount() - part);
            remainingAmount -= part;
        }
        inventory.setContents(contents);
        return removed;
    }

}
