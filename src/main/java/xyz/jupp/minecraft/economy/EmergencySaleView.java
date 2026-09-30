package xyz.jupp.minecraft.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static xyz.jupp.minecraft.economy.ShopView.fail;
import static xyz.jupp.minecraft.economy.ShopView.named;
import static xyz.jupp.minecraft.economy.ShopView.pane;

/** The state's emergency sale while it is broke (rules in {@link Bankruptcy}), opened from the shop. */
public final class EmergencySaleView implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int SLOT_INFO = 4;
    private static final int ITEMS_START = 10;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_CLOSE = 26;
    private static final long CLICK_COOLDOWN_MILLIS = 300;

    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Material> slots = new HashMap<>();
    private List<Material> bought;
    private boolean busy;
    private long ignoreClicksUntil;

    private EmergencySaleView(Player viewer, List<Material> bought) {
        this.viewer = viewer;
        this.bought = bought;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.section("§4§lNotverkauf §8» §7Staat"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static void open(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.supplyAsync(() -> Bankruptcy.boughtToday(uuid), bought -> {
            if (!player.isOnline()) return;
            EmergencySaleView view = new EmergencySaleView(player, bought);
            view.render();
            player.openInventory(view.inventory);
            player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 0.6f, 0.8f);
        });
    }

    private void render() {
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        slots.clear();
        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);
        inventory.setItem(SLOT_BACK, named(Material.ARROW, "§f◀ Zurück zum Händler", List.of()));
        inventory.setItem(SLOT_CLOSE, named(Material.BARRIER, "§cSchließen", List.of()));

        List<Material> sale = Bankruptcy.sale();
        if (sale.isEmpty()) {
            inventory.setItem(SLOT_INFO, named(Material.LIME_DYE, "§aKein Notverkauf", List.of("§7Der Staat ist flüssig.")));
            return;
        }
        inventory.setItem(SLOT_INFO, named(Material.REDSTONE_BLOCK, "§4§lNotverkauf des Staates", List.of(
                "§7Der Staat ist pleite und verkauft heute",
                "§7diese Waren günstiger. Der ganze Preis",
                "§7geht an die Staatskasse, ohne Steuer.",
                "§8Jedes Stück einmal am Tag pro Spieler.")));
        for (int i = 0; i < sale.size() && i < 7; i++) {
            MarketItem item = Market.get(sale.get(i));
            if (item == null) continue;
            int slot = ITEMS_START + i;
            slots.put(slot, item.material());
            inventory.setItem(slot, saleItem(item));
        }
    }

    private ItemStack saleItem(MarketItem item) {
        int price = Bankruptcy.salePrice(item);
        // what it costs otherwise: in the shop, or at Hondo for his goods
        int net = item.buyable() ? item.buyPrice() : Hondo.salePrice(item, item.amount(), 0);
        int normal = net + Taxes.taxOn(net, item.buyable() ? item.taxClass() : Hondo.TAX_CLASS);
        boolean done = bought.contains(item.material());
        ItemStack stack = new ItemStack(item.material(), Math.min(item.amount(), item.material().getMaxStackSize()));
        ItemMeta meta = stack.getItemMeta();
        meta.customName(ShopView.name(item).decoration(TextDecoration.ITALIC, false));
        List<String> lore = new ArrayList<>();
        lore.add("§7" + item.amount() + " Stück für §a" + price + " Schilling");
        if (normal > price) lore.add("§8statt " + normal + (item.buyable() ? " im Laden" : " bei Hondo") + " (inkl. Steuer)");
        lore.add("");
        lore.add(done ? "§7Heute schon gekauft." : "§e» Linksklick: kaufen");
        meta.lore(Text.lore(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    /** Short lines for the shop button: what is on sale today. */
    static List<Component> summary() {
        List<Component> lines = new ArrayList<>();
        for (Material material : Bankruptcy.sale()) {
            MarketItem item = Market.get(material);
            if (item != null) lines.add(Text.section("§7• §f" + item.amount() + "× ").append(ShopView.name(item))
                    .append(Text.section(" §a" + Bankruptcy.salePrice(item))).decoration(TextDecoration.ITALIC, false));
        }
        return lines;
    }

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || busy) return;
        if (System.currentTimeMillis() < ignoreClicksUntil) return;
        if (event.getClick() != ClickType.LEFT) return;
        int slot = event.getRawSlot();
        if (slot == SLOT_CLOSE) {
            Tasks.sync(player::closeInventory);
        } else if (slot == SLOT_BACK) {
            ShopView.open(player);
        } else if (slots.containsKey(slot) && !bought.contains(slots.get(slot))) {
            buy(player, slots.get(slot));
        }
    }

    private void buy(Player player, Material material) {
        MarketItem item = Market.get(material);
        if (item == null) return;
        int expected = Bankruptcy.salePrice(item);
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            Bankruptcy.Outcome outcome;
            try {
                outcome = Bankruptcy.buy(uuid, material, expected);
            } catch (RuntimeException e) {
                outcome = Bankruptcy.Outcome.UNAVAILABLE;
            }
            List<Material> fresh = bought;
            if (outcome == Bankruptcy.Outcome.OK || outcome == Bankruptcy.Outcome.ALREADY_BOUGHT) {
                try {
                    fresh = Bankruptcy.boughtToday(uuid);
                } catch (RuntimeException e) {
                    // a failed read must not stop the delivery
                    fresh = new ArrayList<>(bought);
                    fresh.add(material);
                }
            }
            List<Material> known = fresh;
            Bankruptcy.Outcome result = outcome;
            Runnable onMain = () -> {
                busy = false;
                bought = known;
                switch (result) {
                    case OK -> {
                        HondoView.deliver(uuid, material, item.amount(), true);
                        player.sendMessage(ShopView.receipt("§fNotverkauf: §e" + item.amount() + "× ", item,
                                " §ffür §a" + expected + " Schilling §8(an die Staatskasse)"));
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                    }
                    case ALREADY_BOUGHT -> fail(player, "§fDas hast du heute schon gekauft.");
                    case INSUFFICIENT_FUNDS -> fail(player, "§fDafür fehlen dir Schilling §8(benötigt: " + expected + ")§f.");
                    case PRICE_CHANGED -> fail(player, "§fDer Preis hat sich gerade geändert, bitte prüfe den neuen Preis.");
                    default -> fail(player, "§fDer Notverkauf ist vorbei.");
                }
                render();
            };
            if (!MainThread.run(onMain) && result == Bankruptcy.Outcome.OK) HondoView.keepForLater(uuid, material, item.amount(), true);
        });
    }

}
