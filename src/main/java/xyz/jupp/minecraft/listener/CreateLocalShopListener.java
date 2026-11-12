package xyz.jupp.minecraft.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.block.sign.Side;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.ChestShopRepository;
import xyz.jupp.minecraft.economy.Cash;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.inventory.Loading;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The players' chest shops: a "[Shop]" sign on the front of a chest (price in line 2, amount in line 3) sells what lies
 * in it. The sign keeps owner (uuid), price, amount and the exact item (enchantments, name, durability) in its data:
 * every item works, the buyer gets exactly what the sign promises, a name change does not lose the shop. Left click
 * shows the offer, right click buys; the money goes straight to the owner (no tax, money is how players pay each other).
 * Only the owner opens the chest; hoppers, rails, a joined chest and foreign shop signs are kept away.
 */
public class CreateLocalShopListener implements Listener {

    private static final String SHOP_TITLE = "§6Shop von";
    public static final int MAX_PRICE = 999_999;
    private static final int MAX_AMOUNT = 64;

    private static final NamespacedKey OWNER = new NamespacedKey(Main.getInstance(), "shop_owner");
    private static final NamespacedKey PRICE = new NamespacedKey(Main.getInstance(), "shop_price");
    private static final NamespacedKey AMOUNT = new NamespacedKey(Main.getInstance(), "shop_amount");
    private static final NamespacedKey ITEM = new NamespacedKey(Main.getInstance(), "shop_item");
    private static final NamespacedKey OWNER_NAME = new NamespacedKey(Main.getInstance(), "shop_owner_name");
    // a template larger than this, unpacked (a shulker box full of books ...), would make every purchase and preview expensive
    private static final int MAX_TEMPLATE_BYTES = 128 * 1024;
    private static final long PREVIEW_COOLDOWN_MILLIS = 400;
    private static final Map<UUID, Long> previewUntil = new ConcurrentHashMap<>();

    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /** What a shop sign sells: amount pieces like item (one piece, the template) for price. */
    record Shop(UUID owner, String ownerName, int price, int amount, ItemStack item) {}

    /**
     * The owner of a shop sign, null for any other sign; cheap (the item is not read). A shop sign from before the stored
     * offers (only its text) belongs to the player named on it; while the server does not know that name, to nobody
     * (protected for everyone) until it is migrated.
     */
    static @Nullable UUID owner(@NotNull Sign sign) {
        String owner = sign.getPersistentDataContainer().get(OWNER, PersistentDataType.STRING);
        if (owner == null) return legacy(sign) ? legacyOwner(sign) : null;
        try {
            return UUID.fromString(owner);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean legacy(Sign sign) {
        return SHOP_TITLE.equals(line(sign, 0));
    }

    private static UUID legacyOwner(Sign sign) {
        String name = Text.strip(line(sign, 1));
        org.bukkit.OfflinePlayer known = name.isEmpty() ? null : Bukkit.getOfflinePlayerIfCached(name);
        return known != null ? known.getUniqueId() : UUID.nameUUIDFromBytes(("kpubmc-legacy-shop:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Main thread: a shop sign from before the stored offers gets them from its text (owner, material, amount, price),
     * so it keeps selling; the stock is matched against a plain item of the material, as the old shops did.
     */
    private static void migrate(Sign sign) {
        if (sign.getPersistentDataContainer().has(OWNER) || !legacy(sign)) return;
        String name = Text.strip(line(sign, 1));
        org.bukkit.OfflinePlayer known = name.isEmpty() ? null : Bukkit.getOfflinePlayerIfCached(name);
        if (known == null) return;
        Material material = Material.getMaterial(Text.strip(line(sign, 2)));
        String[] parts = Text.strip(line(sign, 3)).split(" - ");
        if (material == null || !material.isItem() || parts.length < 2) return;
        int amount = parseNumber(parts[0].replace("Stk.", "").trim());
        int price = parseNumber(parts[1].replaceAll("[^0-9.]", ""));
        if (amount <= 0 || amount > MAX_AMOUNT || price <= 0) return;
        if (!(sign.getBlock().getState() instanceof Sign snapshot)) return;
        PersistentDataContainer data = snapshot.getPersistentDataContainer();
        data.set(OWNER, PersistentDataType.STRING, known.getUniqueId().toString());
        data.set(OWNER_NAME, PersistentDataType.STRING, name);
        data.set(PRICE, PersistentDataType.INTEGER, price);
        data.set(AMOUNT, PersistentDataType.INTEGER, amount);
        data.set(ITEM, PersistentDataType.BYTE_ARRAY, new ItemStack(material).serializeAsBytes());
        snapshot.update(true, false);
    }

    /** The whole offer of a shop sign, null for any other sign or one whose item cannot be read any more. */
    static @Nullable Shop shop(@NotNull Sign sign) {
        UUID owner = owner(sign);
        PersistentDataContainer data = sign.getPersistentDataContainer();
        Integer price = data.get(PRICE, PersistentDataType.INTEGER);
        Integer amount = data.get(AMOUNT, PersistentDataType.INTEGER);
        byte[] item = data.get(ITEM, PersistentDataType.BYTE_ARRAY);
        if (owner == null || price == null || amount == null || item == null) return null;
        String name = data.get(OWNER_NAME, PersistentDataType.STRING);
        if (name == null) name = Text.strip(line(sign, 1));
        try {
            return new Shop(owner, name, price, amount, ItemStack.deserializeBytes(item));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String line(Sign sign, int index) {
        String line = Text.section(sign.getSide(Side.FRONT).line(index));
        return line == null ? "" : line;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        previewUntil.remove(event.getPlayer().getUniqueId());
    }

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
        if (!(clickedBlock.getState(false) instanceof Sign legacySign)) return;
        if (legacy(legacySign) && !legacySign.getPersistentDataContainer().has(OWNER)) migrate(legacySign);
        if (!(clickedBlock.getState(false) instanceof Sign sign)) return;
        UUID owner = owner(sign);
        if (owner == null) return;

        Player player = event.getPlayer();
        // runs for cancelled clicks too (spawn protection), so a caught prisoner is turned away here
        if (JailHandler.isCaughtPrisoner(player)) return;

        if (owner.equals(player.getUniqueId())) {
            // the owner may break the sign (left click); editing it would leave the stored offer behind
            if (event.getAction().isRightClick()) {
                event.setCancelled(true);
                player.sendMessage(Main.getChatPrefix() + "§fZum Ändern das Schild abbauen und neu setzen.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            }
            return;
        }
        event.setCancelled(true);
        // a right click comes once per hand
        if (event.getHand() != EquipmentSlot.HAND) return;
        // the throttles come before the item is read: holding the mouse button sends many clicks
        boolean left = event.getAction().isLeftClick();
        if (left ? !previewed(player) : !event.getAction().isRightClick() || !Loading.allow(player.getUniqueId())) return;
        Shop shop = shop(sign);
        if (shop == null) {
            // the item can no longer be read (a removed datapack ...): the shop stays protected, but sells nothing
            player.sendMessage(Main.getChatPrefix() + "§fDieser Shop ist gerade außer Betrieb.");
            return;
        }

        // the chest the sign hangs on, nothing else (any chest next to the sign would sell someone else's goods)
        Block attachedChest = attachedChest(clickedBlock);
        if (attachedChest == null || !(attachedChest.getState(false) instanceof Chest chest)) return;
        int stock = count(chest.getInventory(), shop.item());

        if (left) {
            preview(player, shop, stock);
            return;
        }
        if (stock < shop.amount()) {
            player.sendMessage(Main.getChatPrefix() + "§fDer Shop von §6" + ownerName(shop) + " §fist gerade leer.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }
        buy(player, shop, attachedChest);
    }

    // left click: what exactly is for sale (the item shows its enchantments when hovered)
    private static void preview(Player player, Shop shop, int stock) {
        Component line = Text.section(Main.getChatPrefix() + "§fShop von §6" + ownerName(shop) + "§f: §e" + shop.amount() + "× ")
                .append(itemName(shop.item()).hoverEvent(shop.item().asHoverEvent()))
                .append(Text.section(" §ffür §a" + Items.format(shop.price()) + " Schilling"));
        player.sendMessage(line);
        player.sendMessage(Text.section("§8» §7Rechtsklick zum Kaufen §8| §7Vorrat: §f" + (stock / shop.amount()) + " Mal"));
    }

    // the name stored with the shop: no player file read on the main thread
    private static String ownerName(Shop shop) {
        return shop.ownerName().isEmpty() ? "?" : shop.ownerName();
    }

    // a short pause between previews (and 'empty' notices) per player, holding the mouse button sends many clicks
    private static boolean previewed(Player player) {
        long now = System.currentTimeMillis();
        Long until = previewUntil.get(player.getUniqueId());
        if (until != null && now < until) return false;
        previewUntil.put(player.getUniqueId(), now + PREVIEW_COOLDOWN_MILLIS);
        return true;
    }

    // the custom name or the item's own (translated by the client), with a mark for enchanted goods
    private static Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Component name = meta != null && meta.hasCustomName() && meta.customName() != null
                ? meta.customName() : Component.translatable(item.getType().translationKey());
        if (!item.getEnchantments().isEmpty() || (meta != null && meta.hasEnchants())) name = name.append(Component.text(" ✦"));
        return name.colorIfAbsent(NamedTextColor.GREEN);
    }

    private static boolean isChest(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
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
            if (!(block.getState(false) instanceof Sign sign)) continue;
            UUID owner = owner(sign);
            if (owner != null && !owner.equals(player.getUniqueId())) return true;
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


    // the buyer pays on a worker first, so the goods are only taken out of the chest for a covered purchase
    private static void buy(Player player, Shop shop, Block chestBlock) {
        UUID buyer = player.getUniqueId();
        Tasks.async(() -> {
            boolean paid;
            try {
                paid = ChestShopRepository.pay(buyer, shop.owner(), shop.price());
            } catch (RuntimeException e) {
                notifyBuyer(player, "§cDas ging gerade nicht, versuch es gleich nochmal.");
                return;
            }
            if (!paid) {
                notifyBuyer(player, "§cDein Konto ist aktuell leider nicht ausreichend gedeckt.");
                return;
            }
            // the server stops before the hand-over: the money back
            if (!MainThread.run(() -> handOver(player, chestBlock, shop))) ChestShopRepository.refund(buyer, shop.owner(), shop.price());
        });
    }

    // main thread: takes the goods out of the chest (looked up again, it may be gone by now), the bookings run async
    // (right away during the shutdown, when this runs from MainThread.runPending and nothing can be scheduled)
    private static void handOver(Player player, Block chestBlock, Shop shop) {
        UUID buyer = player.getUniqueId();
        // left meanwhile: the goods would go into an inventory that is no longer saved
        if (!player.isOnline()) {
            Tasks.asyncOrNow(() -> ChestShopRepository.refund(buyer, shop.owner(), shop.price()));
            return;
        }
        List<ItemStack> goods = chestBlock.getState(false) instanceof Chest chest ? removeItems(chest.getInventory(), shop.item(), shop.amount()) : null;
        if (goods == null) {
            Tasks.asyncOrNow(() -> ChestShopRepository.refund(buyer, shop.owner(), shop.price()));
            player.sendMessage(Main.getChatPrefix() + "§fDer Shop von §6" + ownerName(shop) + " §fist gerade nicht ausreichend gefüllt.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }
        Material material = shop.item().getType();
        Tasks.asyncOrNow(() -> ChestShopRepository.sold(shop.owner(), buyer, material, shop.amount(), shop.price()));

        player.getInventory().addItem(goods.toArray(ItemStack[]::new)).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        String owner = ownerName(shop);
        Logger.console(String.format("%s bought %s(%d) from %s for %d", player.getName(), material.name(), shop.amount(), owner, shop.price()));

        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
        player.sendMessage(Text.section(Main.getChatPrefix() + "Du hast §6" + shop.amount() + "× ").append(itemName(shop.item()))
                .append(Text.section(" §fvon §a" + owner + " §ffür §a" + Items.format(shop.price()) + " Schilling §ferworben.")));

        Player seller = Bukkit.getPlayer(shop.owner());
        if (seller != null) {
            seller.playSound(seller.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
            seller.sendMessage(Text.section(Main.getChatPrefix() + "§a" + player.getName() + " §fhat aus deinem Shop §6" + shop.amount() + "× ")
                    .append(itemName(shop.item())).append(Text.section(" §fgekauft: §a+" + Items.format(shop.price()) + " Schilling")));
        }
    }

    private static void notifyBuyer(Player player, String message) {
        MainThread.run(() -> {
            player.sendMessage(Main.getChatPrefix() + message);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        });
    }

    // last of all (a later cancel would leave the stored offer on a sign without the shop text)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLocalShopSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Block signBlock = event.getBlock();
        // a shop sign is not edited (its stored offer would no longer match the text): break it and place a new one
        if (signBlock.getState(false) instanceof Sign existing && owner(existing) != null) {
            event.setCancelled(true);
            player.sendMessage(Main.getChatPrefix() + "§cEin Shop-Schild wird nicht bearbeitet: abbauen und neu setzen.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
            return;
        }
        String title = Text.section(event.line(0));
        if (title == null || !"[Shop]".equalsIgnoreCase(title.trim())) return;
        // the offer is read from the front: a shop text on the back would sell nothing
        if (event.getSide() != Side.FRONT) {
            refuse(event, "§cBitte die Vorderseite des Schilds beschriften.");
            return;
        }

        int price = parseNumber(Text.section(event.line(1)));
        int amount = parseNumber(Text.section(event.line(2)));

        if (price <= 0 || price > MAX_PRICE) {
            refuse(event, "§cDer Preis (2. Zeile) muss zwischen 1 und " + Items.format(MAX_PRICE) + " §a" + Main.getCurrencyName() + " §cliegen. §7(z. B. 1500, 1.500 oder 1,5k)");
            return;
        }
        // the sign shows prices from 10.000 on shortened: only prices it can show exactly
        if ((price >= 100_000 && price % 1_000 != 0) || (price >= 10_000 && price % 100 != 0)) {
            refuse(event, "§cAb 10.000 bitte in 100er-Schritten, ab 100.000 in 1.000er-Schritten §7(z. B. 12,5k oder 150k)§c.");
            return;
        }
        if (amount <= 0 || amount > MAX_AMOUNT) {
            refuse(event, "§cDie Menge (3. Zeile) muss zwischen 1 und " + MAX_AMOUNT + " liegen.");
            return;
        }

        if (!(signBlock.getBlockData() instanceof WallSign wallSign)) {
            refuse(event, "§cBitte platziere das Shop-Schild an der Vorderseite einer Truhe.");
            return;
        }
        Block attachedBlock = signBlock.getRelative(wallSign.getFacing().getOppositeFace());
        if (attachedBlock.getType() != Material.CHEST || !(attachedBlock.getState(false) instanceof Chest chest)) {
            refuse(event, "§cBitte platziere das Shop-Schild an der Vorderseite einer normalen Truhe.");
            return;
        }
        // someone else's shop chest (or its other half) cannot become a second shop
        if (foreignShop(attachedBlock, player)) {
            refuse(event, "§cAn dieser Truhe hängt schon ein fremder Shop.");
            return;
        }
        // a hopper or a hopper minecart's rail under the chest would empty the shop for anyone
        Block otherHalf = otherHalf(attachedBlock);
        if (drainsFromBelow(attachedBlock) || (otherHalf != null && drainsFromBelow(otherHalf))) {
            refuse(event, "§cUnter der Shop-Truhe darf kein Trichter und keine Schiene sein.");
            return;
        }

        // the first stack is the template: every stack in the chest has to be the same item (enchantments, name, damage)
        ItemStack template = template(chest.getInventory());
        if (template == null) {
            refuse(event, "§cIn der Truhe muss mindestens ein Item sein, und alle müssen gleich sein §7(gleiche Verzauberung, Name und Haltbarkeit)§c.");
            return;
        }
        if (Cash.isAnyCash(template)) {
            refuse(event, "§cBargeld verkauft man nicht im Shop. §7(Zum Bezahlen: /zahlen)");
            return;
        }
        byte[] item = template.serializeAsBytes();
        if (rawSize(item) > MAX_TEMPLATE_BYTES) {
            refuse(event, "§cDieses Item ist für einen Shop zu groß §7(z. B. eine volle Shulkerkiste)§c.");
            return;
        }

        event.line(0, Text.section(SHOP_TITLE));
        event.line(1, Text.section("§6" + player.getName()));
        event.line(2, itemName(template));
        event.line(3, Text.section("§a" + amount + " Stk. §8- §a" + shortPrice(price) + " S."));

        // the offer goes into the sign's data right away, on the live sign (the new text is put on it after the event)
        if (!(signBlock.getState(false) instanceof Sign live)) {
            refuse(event, "§cDas Schild konnte nicht beschrieben werden.");
            return;
        }
        PersistentDataContainer data = live.getPersistentDataContainer();
        data.set(OWNER, PersistentDataType.STRING, player.getUniqueId().toString());
        data.set(OWNER_NAME, PersistentDataType.STRING, player.getName());
        data.set(PRICE, PersistentDataType.INTEGER, price);
        data.set(AMOUNT, PersistentDataType.INTEGER, amount);
        data.set(ITEM, PersistentDataType.BYTE_ARRAY, item);

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 2f);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 2f, 2f);
        player.sendMessage(Main.getChatPrefix() + "§aDein Shop steht. §7Linksklick zeigt Kunden das Angebot, Rechtsklick kauft.");
    }

    // the unpacked size of a serialized item (the bytes are compressed, text compresses a thousandfold)
    private static long rawSize(byte[] item) {
        try (java.io.InputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(item))) {
            return in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (java.io.IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void refuse(SignChangeEvent event, String message) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        player.sendMessage(Main.getChatPrefix() + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
    }

    /** 1500, 1.500, 1,5k, 15k, 2m; 0 for anything else. */
    public static int parseNumber(@Nullable String raw) {
        if (raw == null) return 0;
        String text = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.isEmpty()) return 0;
        long factor = 1;
        if (text.endsWith("k")) {
            factor = 1_000;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("m")) {
            factor = 1_000_000;
            text = text.substring(0, text.length() - 1);
        }
        // digits with dots or a comma only: no sign, no exponent, no other digit scripts
        if (!text.matches("[0-9][0-9.,]*")) return 0;
        try {
            if (factor == 1) {
                if (text.contains(",")) return 0;
                long value = Long.parseLong(text.replace(".", ""));
                return value <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, value);
            }
            double value = Double.parseDouble(text.replace(",", "."));
            return value <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.round(value * factor));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // fits on a sign: 1.500 or 12,5k
    static String shortPrice(int price) {
        if (price < 10_000) return Items.format(price);
        double thousands = price / 1000.0;
        String text = thousands >= 100 || thousands == Math.floor(thousands)
                ? String.valueOf(Math.round(Math.floor(thousands)))
                : String.format(Locale.GERMANY, "%.1f", Math.floor(thousands * 10) / 10);
        return text + "k";
    }

    // one piece of the first stack, if every stack of the chest is like it
    private static @Nullable ItemStack template(Inventory inventory) {
        ItemStack template = null;
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.isEmpty()) continue;
            if (template == null) {
                template = item.asOne();
            } else if (!item.isSimilar(template)) {
                return null;
            }
        }
        return template;
    }

    private static int count(Inventory inventory, ItemStack template) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.isSimilar(template)) count += item.getAmount();
        }
        return count;
    }

    // main thread only: the removed stacks as they were in the chest; null (and nothing removed) if it has too few
    private static @Nullable List<ItemStack> removeItems(Inventory inventory, ItemStack template, int amountToRemove) {
        ItemStack[] contents = inventory.getContents();
        if (count(inventory, template) < amountToRemove) return null;
        List<ItemStack> removed = new ArrayList<>();
        int remainingAmount = amountToRemove;
        for (ItemStack item : contents) {
            if (remainingAmount <= 0) break;
            if (item == null || !item.isSimilar(template)) continue;
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
