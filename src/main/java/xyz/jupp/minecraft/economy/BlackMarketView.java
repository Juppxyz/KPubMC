package xyz.jupp.minecraft.economy;

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
import xyz.jupp.minecraft.utils.BlackMarketHandler;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static xyz.jupp.minecraft.economy.ShopView.named;
import static xyz.jupp.minecraft.economy.ShopView.pane;

/**
 * Morpheus' black market: no fixed price, the player haggles with him (see {@link Haggle}).
 * Tax free, but a buyer is caught with a chance of {@link #CAUGHT_CHANCE} and is wanted for {@link #WANTED_HOURS} hours.
 */
public final class BlackMarketView implements InventoryHolder {

    private static final double CAUGHT_CHANCE = 0.10;
    private static final int WANTED_HOURS = 24;
    private static final int MAX_OFFER = 1_000_000;
    private static final long CLICK_COOLDOWN_MILLIS = 400;

    private static final int SLOT_ITEM = 4;
    private static final int SLOT_OFFER = 13;
    private static final int SLOT_SUBMIT = 20;
    private static final int SLOT_MOOD = 22;
    private static final int SLOT_ACCEPT = 24;
    // step buttons: slot -> change of the offer
    private static final Map<Integer, Integer> STEPS = Map.of(10, -1_000, 11, -100, 15, 100, 16, 1_000);

    private static final List<String> COUNTERS = List.of(
            "%d? Lächerlich. Für %d gehört es dir.",
            "Du willst mich wohl ruinieren. %2$d, nicht weniger.",
            "Hmm... na gut. %2$d, mein letztes Wort. Vielleicht.");

    private final Player viewer;
    private final int offerId;
    private final Inventory inventory;
    private int offer;
    private long ignoreClicksUntil;

    private BlackMarketView(Player viewer, int offerId, int offer) {
        this.viewer = viewer;
        this.offerId = offerId;
        this.offer = offer;
        this.inventory = Bukkit.createInventory(this, 27, Text.section("§0§oMarkt des " + Main.getBlackMarketDealerVillagerName()));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private static String prefix() {
        return Main.getBlackMarketDealerVillagerName() + " §7» §f§o";
    }


    /* open (main thread) */

    public static void open(@NotNull Player player) {
        if (!BlackMarketHandler.isOpen() || BlackMarketHandler.getCurrentBlackMarketItem() == null) {
            player.playSound(player, Sound.BLOCK_ENDER_CHEST_CLOSE, 2f, 2f);
            player.sendMessage(prefix() + "Ich kann dir leider gerade nix anbieten. Komm später wieder.");
            return;
        }
        if (Haggle.isRefused(player.getUniqueId())) {
            player.playSound(player, Sound.ENTITY_VINDICATOR_AMBIENT, 1f, 0.8f);
            player.sendMessage(prefix() + "Dich kenne ich. Verschwinde!");
            return;
        }
        Haggle.Talk talk = Haggle.talk(player.getUniqueId());
        // the suggested first offer stays above the insult line
        int start = talk.lastOffer() > 0 ? talk.lastOffer() : (int) (Math.round(talk.asking() * 0.7 / 100.0) * 100);
        BlackMarketView view = new BlackMarketView(player, talk.offerId(), start);
        view.render();
        player.openInventory(view.inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.2f);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 0.8f);
        if (talk.lastOffer() == 0) player.sendMessage(prefix() + "Das hier? Nicht unter " + talk.asking() + ". Und nur bar. Aber rede ruhig.");
    }

    private void render() {
        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, frame);
        Haggle.Talk talk = Haggle.talk(viewer.getUniqueId());

        ItemStack item = BlackMarketHandler.getCurrentBlackMarketItem();
        if (item != null) {
            ItemMeta meta = item.getItemMeta();
            List<String> lore = new ArrayList<>(Text.legacyLore(meta.lore()) == null ? List.of() : Text.legacyLore(meta.lore()));
            lore.add("");
            lore.add("§7Morpheus will: §c" + talk.asking() + " Schilling");
            lore.add("§7Dein Bargeld: §f" + Cash.total(viewer) + " Schilling");
            lore.add("§7Nur Bargeld. Steuerfrei, aber nicht ohne Risiko.");
            meta.lore(Text.lore(lore));
            item.setItemMeta(meta);
            inventory.setItem(SLOT_ITEM, item);
        }

        STEPS.forEach((slot, step) -> inventory.setItem(slot, named(step < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                (step < 0 ? "§c-" : "§a+") + String.format("%,d", Math.abs(step)).replace(',', '.'), List.of("§7Angebot ändern"))));
        inventory.setItem(SLOT_OFFER, named(Material.GOLD_NUGGET, "§eDein Angebot: " + Main.getCurrencyName(offer),
                List.of("§7Stell dein Angebot mit den", "§7Knöpfen links und rechts ein.")));
        inventory.setItem(SLOT_SUBMIT, named(Material.LIME_CONCRETE, "§a§lAngebot machen",
                List.of("§7Du bietest: §f" + offer + " Schilling", "", "§e» Linksklick")));
        inventory.setItem(SLOT_MOOD, switch (talk.patience()) {
            case 3 -> named(Material.LIME_DYE, "§aMorpheus wirkt gelassen", List.of());
            case 2 -> named(Material.YELLOW_DYE, "§eMorpheus wirkt genervt", List.of());
            default -> named(Material.RED_DYE, "§cMorpheus ist kurz vorm Platzen", List.of());
        });
        inventory.setItem(SLOT_ACCEPT, named(Material.GOLD_INGOT, "§6Für " + talk.asking() + " Schilling kaufen",
                List.of("§7Morpheus' Preis annehmen", "", "§e» Linksklick")));
    }


    /* clicks (main thread) */

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) return;
        if (System.currentTimeMillis() < ignoreClicksUntil) return;
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
        if (offerId != BlackMarketHandler.currentOffer()) {
            player.sendMessage(prefix() + "Zu spät, das ist weg.");
            Tasks.sync(player::closeInventory);
            return;
        }
        int slot = event.getRawSlot();

        Integer step = STEPS.get(slot);
        if (step != null) {
            offer = Math.max(0, Math.min(MAX_OFFER, offer + step));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, step > 0 ? 1.4f : 0.9f);
            render();
            return;
        }
        if (click != ClickType.LEFT) return;
        if (slot == SLOT_ACCEPT) {
            buy(player, Haggle.talk(player.getUniqueId()).asking());
        } else if (slot == SLOT_SUBMIT) {
            makeOffer(player);
        }
    }

    private void makeOffer(Player player) {
        Haggle.Result result = Haggle.offer(player.getUniqueId(), offer);
        switch (result.answer()) {
            case DEAL -> buy(player, result.price());
            case COUNTER -> {
                String line = COUNTERS.get(ThreadLocalRandom.current().nextInt(COUNTERS.size()));
                player.sendMessage(prefix() + String.format(line, offer, result.price()));
                player.playSound(player, Sound.ENTITY_VINDICATOR_AMBIENT, 1f, 1f);
                ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
                render();
            }
            case INSULTED -> {
                player.sendMessage(prefix() + "§cVon so jemandem lasse ich mich nicht abziehen! Verschwinde.");
                player.playSound(player, Sound.ENTITY_VINDICATOR_HURT, 1f, 0.7f);
                Tasks.sync(player::closeInventory);
            }
            case GAVE_UP -> {
                player.sendMessage(prefix() + "§cGenug geredet. Du verschwendest meine Zeit.");
                player.playSound(player, Sound.ENTITY_VINDICATOR_HURT, 1f, 0.7f);
                Tasks.sync(player::closeInventory);
            }
        }
    }

    // tax free and only cash, paid on the main thread; the item is reserved first, so two players cannot buy the same one
    private void buy(Player player, int agreed) {
        // the smallest note is 10: a price is always paid exactly, never with lost change
        int price = (agreed + 9) / 10 * 10;
        if (!BlackMarketHandler.reserve(offerId)) {
            player.sendMessage(prefix() + "Zu spät, das hat gerade jemand anderes gekauft.");
            Tasks.sync(player::closeInventory);
            return;
        }
        if (!Cash.pay(player, price)) {
            BlackMarketHandler.release(offerId);
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
            player.sendMessage(prefix() + "Ich nehme nur Bares, und davon hast du zu wenig. §7(" + price + " Schilling in Scheinen, du hast "
                    + Cash.total(player) + ")");
            return;
        }
        ItemStack item = BlackMarketHandler.getCurrentBlackMarketItem();
        Logger.console("black market: " + player.getUniqueId() + " bought " + (item == null ? "?" : item.getType()) + " for " + price + " cash");
        if (item != null) {
            player.getInventory().addItem(item).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        }
        player.sendMessage(Main.getChatPrefix() + "§c-" + price + " " + Main.getCurrencyName() + " §7(bar, steuerfrei)");
        player.sendMessage(prefix() + "Besuche mich gerne bald wieder! Viel Spaß damit.");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 0.2f);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 2f, 2f);
        Haggle.finish(player.getUniqueId());
        Tasks.sync(player::closeInventory);
        BlackMarketHandler.forceReroll();
        if (ThreadLocalRandom.current().nextDouble() < CAUGHT_CHANCE) {
            player.sendMessage(prefix() + "Psst... ich glaube, man hat dich gesehen. Pass auf dich auf.");
            JailHandler.markWanted(player, WANTED_HOURS, "Schwarzmarkthandel");
        }
    }

}
