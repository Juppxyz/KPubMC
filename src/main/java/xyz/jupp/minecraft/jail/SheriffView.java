package xyz.jupp.minecraft.jail;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.economy.Loans;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Sheriff's counter: hand in bounty heads ({@link Sheriff#handIn}), see who is wanted and what is on their head, and
 * put money of one's own on a wanted player's head (any amount plus {@link Bounties#PLEDGE_FEE} for the state,
 * announced without the sponsor's name). Database reads and bookings on workers, the inventory on the main thread.
 */
public final class SheriffView implements InventoryHolder {

    private enum Page { LIST, PLEDGE }

    private static final int SIZE = 54;
    private static final int SLOT_HAND_IN = 0;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_CLOSE = 8;
    private static final int WANTED_FIRST = 9;
    private static final int WANTED_LAST = 44;
    private static final int SLOT_MINE = 49;
    // pledge page
    private static final int SLOT_TARGET = 13;
    private static final int SLOT_AMOUNT = 31;
    private static final Map<Integer, Integer> STEPS = Map.of(27, -10_000, 28, -1_000, 29, -100, 33, 100, 34, 1_000, 35, 10_000);
    private static final int SLOT_CONFIRM = 40;
    private static final int SLOT_BACK = 45;
    private static final int START_AMOUNT = 500;
    private static final int MIN_AMOUNT = 100;
    private static final int MAX_AMOUNT = 1_000_000;

    // one booking per player at a time, also across two open menus
    private static final Set<UUID> booking = ConcurrentHashMap.newKeySet();

    private record Wanted(UUID uuid, String name, long jailEnd, int stateBounty, long pledged) {}

    private record Mine(String name, long amount) {}

    private record State(List<Wanted> wanted, List<Mine> mine) {}

    private final Player viewer;
    private final Inventory inventory;
    private State state;
    private Page page = Page.LIST;
    private @Nullable Wanted target;
    private int amount = START_AMOUNT;
    private final Map<Integer, Wanted> wantedSlots = new HashMap<>();
    private boolean busy;
    // clicks right after a new page are ignored (a double click would hit the next page's buttons)
    private long ignoreClicksUntil;
    private static final long CLICK_COOLDOWN_MILLIS = 300;

    private SheriffView(Player viewer, State state) {
        this.viewer = viewer;
        this.state = state;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.section("§9Sheriff §8» §7Kopfgelder"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Main thread: the Sheriff's menu (the NPC's right click). */
    public static void open(@NotNull Player player) {
        if (JailHandler.isCaughtPrisoner(player)) {
            player.sendMessage(Sheriff.PREFIX + "Erst sitzt du deine Strafe ab.");
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.supplyAsync(() -> load(uuid), state -> {
            if (!player.isOnline()) return;
            SheriffView view = new SheriffView(player, state);
            view.render();
            player.openInventory(view.inventory);
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        });
    }

    // worker: who is wanted, their bounties, the viewer's own open pledges
    private static State load(UUID viewer) {
        List<Wanted> wanted = new ArrayList<>();
        for (PlayerRepository.PlayerData data : PlayerRepository.getWantedPlayers()) {
            if (wanted.size() > WANTED_LAST - WANTED_FIRST) break;
            String name = Bukkit.getOfflinePlayer(data.uuid()).getName();
            wanted.add(new Wanted(data.uuid(), name == null ? "?" : name, data.jailEnd(),
                    Loans.bounty(data.uuid(), data.jailEnd()), Bounties.pledged(data.uuid())));
        }
        List<Mine> mine = Database.query("SELECT wanted_uuid, SUM(amount) FROM bounty_pledges WHERE sponsor_uuid = ? "
                        + "AND paid_at IS NULL AND refunded_at IS NULL GROUP BY wanted_uuid ORDER BY 2 DESC LIMIT 10", row -> {
            String name = Bukkit.getOfflinePlayer(row.getObject(1, UUID.class)).getName();
            return new Mine(name == null ? "?" : name, row.getLong(2));
        }, viewer);
        return new State(wanted, mine);
    }


    /* render (main thread) */

    private void render() {
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        ItemStack frame = Items.pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);
        wantedSlots.clear();
        inventory.setItem(SLOT_CLOSE, Items.named(Material.BARRIER, "§cSchließen", List.of()));
        if (page == Page.PLEDGE && target != null) renderPledge(target);
        else renderList();
    }

    private void renderList() {
        int heads = 0;
        for (ItemStack item : viewer.getInventory().getContents()) {
            if (Bounties.read(item) != null) heads += item.getAmount();
        }
        inventory.setItem(SLOT_HAND_IN, Items.glow(Items.named(Material.PLAYER_HEAD, heads > 0 ? "§a§lKöpfe abgeben" : "§7Köpfe abgeben", heads > 0
                ? List.of("§7Du hast §f" + heads + " §7Kopf/Köpfe dabei.", "", "§e» Klick: abgeben und kassieren")
                : List.of("§7Bring mir den Kopf eines Gesuchten,", "§7dann zahle ich das Kopfgeld.")), heads > 0));
        inventory.setItem(SLOT_INFO, Items.named(Material.WRITABLE_BOOK, "§9Kopfgelder", List.of(
                "§7Wer einen Gesuchten außerhalb seines Teams",
                "§7erwischt, bekommt dessen Kopf. Hier zahle",
                "§7ich das Kopfgeld des Staates und das der Spieler.",
                "",
                "§7Selbst Kopfgeld aussetzen: einen Gesuchten",
                "§7anklicken. Gebühr: §f" + Bounties.PLEDGE_FEE + " Schilling §7an den Staat.",
                "§7Bleibt der Kopf aus, bekommst du es zurück.")));
        if (state.wanted().isEmpty()) {
            inventory.setItem(22, Items.named(Material.LIME_DYE, "§aGerade wird niemand gesucht", List.of()));
        }
        for (int i = 0; i < state.wanted().size() && WANTED_FIRST + i <= WANTED_LAST; i++) {
            Wanted wanted = state.wanted().get(i);
            int slot = WANTED_FIRST + i;
            wantedSlots.put(slot, wanted);
            List<String> lore = new ArrayList<>();
            lore.add("§7Gesucht: §f" + (wanted.jailEnd() == 0 ? "bis gefasst" : "noch " + hoursLeft(wanted.jailEnd())));
            lore.add("§7Kopfgeld vom Staat: §a" + Items.format(wanted.stateBounty()) + " Schilling");
            if (wanted.pledged() > 0) lore.add("§7Von Spielern: §a" + Items.format(wanted.pledged()) + " Schilling");
            lore.add("§7Dazu §a" + Bounties.TEAM_POINTS + " Team-Punkte");
            lore.add("");
            lore.add(wanted.uuid().equals(viewer.getUniqueId()) ? "§cDas bist du." : "§e» Klick: Kopfgeld aussetzen");
            inventory.setItem(slot, head(wanted, "§c" + wanted.name(), lore));
        }
        List<String> mine = new ArrayList<>();
        if (state.mine().isEmpty()) {
            mine.add("§7Du hast gerade auf niemanden etwas ausgesetzt.");
        } else {
            for (Mine entry : state.mine()) mine.add("§f" + entry.name() + " §7» §a" + Items.format(entry.amount()) + " Schilling");
            mine.add("");
            mine.add("§7Bleibt der Kopf aus, bekommst du es zurück.");
        }
        inventory.setItem(SLOT_MINE, Items.named(Material.GOLD_NUGGET, "§eDeine Kopfgelder", mine));
    }

    private void renderPledge(Wanted wanted) {
        inventory.setItem(SLOT_TARGET, head(wanted, "§cKopfgeld auf " + wanted.name(), List.of(
                "§7Vom Staat: §a" + Items.format(wanted.stateBounty()) + " Schilling",
                "§7Von Spielern bisher: §a" + Items.format(wanted.pledged()) + " Schilling")));
        for (Map.Entry<Integer, Integer> step : STEPS.entrySet()) {
            int delta = step.getValue();
            inventory.setItem(step.getKey(), Items.named(delta < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    (delta < 0 ? "§c" : "§a+") + Items.format(delta), List.of()));
        }
        inventory.setItem(SLOT_AMOUNT, Items.named(Material.GOLD_INGOT, "§e" + Items.format(amount) + " Schilling", List.of(
                "§7Dazu §f" + Bounties.PLEDGE_FEE + " Schilling §7Gebühr an den Staat.")));
        inventory.setItem(SLOT_CONFIRM, Items.named(Material.LIME_CONCRETE, "§a§lKopfgeld aussetzen", List.of(
                "§7Du zahlst §f" + Items.format(amount + Bounties.PLEDGE_FEE) + " Schilling§7:",
                "§f" + Items.format(amount) + " §7auf den Kopf, §f" + Bounties.PLEDGE_FEE + " §7Gebühr.",
                "§7Alle erfahren davon, aber nicht von wem.",
                "",
                "§e» Klick: aussetzen")));
        inventory.setItem(SLOT_BACK, Items.named(Material.ARROW, "§7Zurück", List.of()));
    }

    private static ItemStack head(Wanted wanted, String name, List<String> lore) {
        ItemStack head = Items.named(Material.PLAYER_HEAD, name, lore);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(wanted.uuid());
            meta.setOwningPlayer(owner);
            head.setItemMeta(meta);
        }
        return head;
    }

    private static String hoursLeft(long jailEnd) {
        long hours = Math.max(0, Math.round((jailEnd - System.currentTimeMillis()) / 3_600_000d));
        return hours <= 1 ? "unter 1 Stunde" : hours + " Stunden";
    }


    /* clicks (main thread) */

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || busy) return;
        if (System.currentTimeMillis() < ignoreClicksUntil) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        int slot = event.getRawSlot();
        if (slot == SLOT_CLOSE) {
            Tasks.sync(player::closeInventory);
            return;
        }
        if (page == Page.LIST) {
            if (slot == SLOT_HAND_IN) {
                // the hand-in tells everything in the chat
                Tasks.sync(() -> {
                    player.closeInventory();
                    Sheriff.handIn(player);
                });
                return;
            }
            Wanted wanted = wantedSlots.get(slot);
            if (wanted == null) return;
            if (wanted.uuid().equals(player.getUniqueId())) {
                fail(player, "Auf dich selbst geht das nicht.");
                return;
            }
            target = wanted;
            amount = START_AMOUNT;
            page = Page.PLEDGE;
            render();
            return;
        }
        if (slot == SLOT_BACK) {
            page = Page.LIST;
            render();
            return;
        }
        Integer step = STEPS.get(slot);
        if (step != null) {
            amount = Math.max(MIN_AMOUNT, Math.min(MAX_AMOUNT, amount + step));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            render();
            return;
        }
        if (slot == SLOT_CONFIRM && target != null) pledge(player, target, amount);
    }

    private void pledge(Player player, Wanted wanted, int pledge) {
        if (JailHandler.isCaughtPrisoner(player)) {
            fail(player, "Im Gefängnis geht das nicht.");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!booking.add(uuid)) {
            fail(player, "Einen Moment, deine letzte Buchung läuft noch.");
            return;
        }
        busy = true;
        Tasks.async(() -> {
            Bounties.Pledge result;
            try {
                result = Bounties.pledge(uuid, wanted.uuid(), pledge);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Bounty of {} on {} failed: {}", uuid, wanted.uuid(), e.toString());
                result = null;
            } finally {
                booking.remove(uuid);
            }
            Bounties.Pledge done = result;
            String bound = null;
            try {
                if (done != null && done.outcome() == Bounties.PledgeOutcome.INSUFFICIENT_FUNDS) bound = Loans.lockedMessage(uuid);
            } catch (RuntimeException e) {
                // only the reason of the refusal is missing then
            }
            String reason = bound;
            State fresh;
            try {
                fresh = load(uuid);
            } catch (RuntimeException e) {
                fresh = null;
            }
            State reloaded = fresh;
            MainThread.run(() -> {
                busy = false;
                if (reloaded != null) state = reloaded;
                if (done == null) {
                    fail(player, "Das ging gerade nicht, versuch es gleich nochmal.");
                    return;
                }
                switch (done.outcome()) {
                    case OK -> {
                        Logger.console("Bounty pledge by " + player.getName() + " on " + wanted.name() + ": " + pledge);
                        if (player.isOnline()) {
                            player.sendMessage(Sheriff.PREFIX + "Du hast §a" + Items.format(pledge) + " Schilling §fauf §c" + wanted.name()
                                    + " §fausgesetzt §7(+" + Bounties.PLEDGE_FEE + " Gebühr an den Staat)§f. Bleibt der Kopf aus, bekommst du es zurück.");
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
                        }
                        // without the sponsor's name
                        Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§c§lKOPFGELD §fJemand setzt §a" + Items.format(pledge)
                                + " Schilling §fauf §c" + wanted.name() + " §faus. §7(Von Spielern insgesamt: " + Items.format(done.total()) + ")"));
                        page = Page.LIST;
                    }
                    case NOT_WANTED -> {
                        fail(player, "§f" + wanted.name() + " wird nicht mehr gesucht.");
                        page = Page.LIST;
                    }
                    case INSUFFICIENT_FUNDS -> fail(player, reason != null ? reason : "Dafür reicht dein Konto nicht §7(" + Items.format(pledge)
                            + " + " + Bounties.PLEDGE_FEE + " Gebühr)§f.");
                    case TOO_LOW -> fail(player, "Das ist kein gültiger Betrag.");
                    case UNKNOWN -> {
                        fail(player, "Diesen Spieler kenne ich nicht.");
                        page = Page.LIST;
                    }
                }
                if (player.isOnline() && player.getOpenInventory().getTopInventory() == inventory) render();
            });
        });
    }

    private static void fail(Player player, String message) {
        if (!player.isOnline()) return;
        player.sendMessage(Sheriff.PREFIX + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
    }

}
