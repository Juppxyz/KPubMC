package xyz.jupp.minecraft.economy;

import net.kyori.adventure.text.Component;
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
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.database.TeamRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static xyz.jupp.minecraft.economy.ShopView.countPlain;
import static xyz.jupp.minecraft.economy.ShopView.fail;
import static xyz.jupp.minecraft.economy.ShopView.give;
import static xyz.jupp.minecraft.economy.ShopView.named;
import static xyz.jupp.minecraft.economy.ShopView.pane;
import static xyz.jupp.minecraft.economy.ShopView.takePlain;

/**
 * The GUI of Nomad, the team point dealer: contracts, the open redemption list and the weekly race.
 * Like the shop, a click in an overview only opens a detail page; items are handed over there with one button.
 * All state lives in this holder and is only used on the main thread.
 */
public final class NomadView implements InventoryHolder {

    private enum Tab {
        CONTRACTS("§6Aufträge", Material.WRITABLE_BOOK),
        REDEEM("§aAnkauf", Material.EMERALD),
        RACE("§eWochen-Rennen", Material.GOLDEN_HELMET);

        private final String label;
        private final Material icon;

        Tab(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private enum Page { OVERVIEW, CONTRACT, REDEEM }

    private static final int SIZE = 54;
    private static final int SLOT_CLOSE = 8;
    private static final int MARKER_ROW = 9;
    private static final int CONTENT_START = 18;
    private static final int CONTENT_SIZE = 27;
    private static final int SLOT_BACK = 0;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_ACTION = 22;
    private static final int SLOT_HELP = 40;
    private static final int SLOT_TEAM_POINTS = 49;
    private static final long CLICK_COOLDOWN_MILLIS = 300;
    private static final Material[] PODIUM = {Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.COPPER_BLOCK, Material.STONE, Material.STONE};

    private final Player viewer;
    private final @Nullable String teamID;
    private final Map<Integer, Long> contractSlots = new HashMap<>();
    private final Map<Integer, Material> redeemSlots = new HashMap<>();
    private Inventory inventory;
    private Tab tab = Tab.CONTRACTS;
    private Page page = Page.OVERVIEW;
    private long detailContract;
    private @Nullable Material detailMaterial;
    private boolean actionEnabled;
    private boolean busy;
    private long ignoreClicksUntil;
    // loaded from the database on a worker
    private Map<Long, Nomad.Progress> progress = Map.of();
    private int teamPoints;
    private List<Nomad.RaceEntry> standings = List.of();

    private NomadView(Player viewer, @Nullable String teamID) {
        this.viewer = viewer;
        this.teamID = teamID;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private record State(Map<Long, Nomad.Progress> progress, int teamPoints, List<Nomad.RaceEntry> standings) {}


    /* open / load (main thread) */

    public static void open(@NotNull Player player) {
        String teamID = CacheHandler.getInstance().getPlayerInCache(player).getTeamID();
        NomadView view = new NomadView(player, teamID);
        view.load(() -> {
            if (!player.isOnline()) return;
            view.reopen();
            player.playSound(player.getLocation(), Sound.ENTITY_WANDERING_TRADER_YES, 1f, 1f);
        });
    }

    private void load(Runnable then) {
        Tasks.supplyAsync(() -> new State(
                teamID == null ? Map.of() : Nomad.progress(teamID),
                TeamRepository.getTeamPoints(teamID),
                Nomad.standings(Nomad.weekStart(LocalDate.now(Market.ZONE)))), state -> {
            progress = state.progress();
            teamPoints = state.teamPoints();
            standings = state.standings();
            then.run();
        });
    }

    private void reopen() {
        inventory = Bukkit.createInventory(this, SIZE, title());
        render();
        viewer.openInventory(inventory);
    }

    private Component title() {
        Component prefix = Text.section("§6§lNomad §8» ");
        return switch (page) {
            case OVERVIEW -> prefix.append(Text.section(tab.label));
            case CONTRACT -> prefix.append(Text.section("§6Auftrag"));
            case REDEEM -> detailMaterial == null ? prefix : prefix.append(Component.translatable(detailMaterial.translationKey()));
        };
    }

    private void render() {
        inventory.clear();
        contractSlots.clear();
        redeemSlots.clear();
        actionEnabled = false;
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);

        switch (page) {
            case OVERVIEW -> renderOverview();
            case CONTRACT -> renderContract();
            case REDEEM -> renderRedeem();
        }
        inventory.setItem(SLOT_TEAM_POINTS, teamID == null
                ? named(Material.GRAY_DYE, "§7Du bist in keinem Team", List.of("§7Team-Punkte sammeln nur Teams."))
                : named(Material.GOLD_INGOT, "§fTeam-Punkte: §a" + teamPoints, List.of("§7" + Text.strip(Nomad.teamName(teamID)))));
    }


    /* overviews */

    private void renderOverview() {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = tabs[i] == tab;
            ItemStack item = named(tabs[i].icon, (selected ? "§a§l" : "§f§l") + Text.strip(tabs[i].label),
                    List.of(selected ? "§a✔ Geöffnet" : "§e» Klicken zum Öffnen"));
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(selected);
            item.setItemMeta(meta);
            inventory.setItem(i, item);
        }
        inventory.setItem(SLOT_CLOSE, named(Material.BARRIER, "§cSchließen", List.of()));
        inventory.setItem(MARKER_ROW + tab.ordinal(), named(Material.LIME_STAINED_GLASS_PANE, "§a▲ " + tab.label, List.of()));
        for (int slot = CONTENT_START; slot < CONTENT_START + CONTENT_SIZE; slot++) inventory.setItem(slot, null);

        switch (tab) {
            case CONTRACTS -> renderContracts();
            case REDEEM -> renderRedeemList();
            case RACE -> renderRace();
        }
    }

    private void renderContracts() {
        for (Nomad.Contract contract : Nomad.contracts()) {
            int slot = 20 + contract.slot() * 2;
            ItemStack item = contractItem(contract);
            ItemMeta meta = item.getItemMeta();
            List<String> lore = contractLore(contract);
            Nomad.Progress state = progress.get(contract.id());
            if (teamID != null && (state == null || !state.completed())) {
                lore.add("");
                lore.add("§e» Klicken zum Abliefern");
            }
            meta.lore(Text.lore(lore));
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            contractSlots.put(slot, contract.id());
        }
        inventory.setItem(SLOT_HELP, named(Material.BOOK, "§6So funktioniert's", List.of(
                "§7Nomad sucht Waren für seine Karawane.",
                "§7Liefert sie als Team: jeder kann",
                "§7einen Teil beitragen.",
                "§7Ist der Auftrag voll, bekommt euer",
                "§7Team die Belohnung.",
                "§7Jeden Tag kommt ein neuer Auftrag.")));
    }

    private ItemStack contractItem(Nomad.Contract contract) {
        ItemStack item = new ItemStack(contract.material());
        ItemMeta meta = item.getItemMeta();
        meta.customName(Text.section("§e" + contract.required() + "× ").append(Component.translatable(contract.material().translationKey())));
        Nomad.Progress state = progress.get(contract.id());
        meta.setEnchantmentGlintOverride(state != null && state.completed());
        item.setItemMeta(meta);
        return item;
    }

    private List<String> contractLore(Nomad.Contract contract) {
        Nomad.Progress state = progress.get(contract.id());
        int delivered = state == null ? 0 : state.delivered();
        List<String> lore = new ArrayList<>();
        if (contract.slot() == 2) lore.add("§d★ Sonderauftrag");
        lore.add("§7Belohnung: §a+" + contract.reward() + " Team-Punkte");
        if (state != null && state.completed()) {
            lore.add("§a✔ Erledigt!");
        } else {
            lore.add("§7Geliefert: §f" + delivered + "§7/§f" + contract.required() + " " + bar(delivered, contract.required()));
        }
        lore.add("§7Endet in: §f" + remaining(contract.endsAt()));
        return lore;
    }

    private void renderRedeemList() {
        List<Nomad.Redeemable> list = new ArrayList<>(Nomad.redeemables());
        list.sort((a, b) -> Boolean.compare(Nomad.isHot(b.material()), Nomad.isHot(a.material())));
        int slot = CONTENT_START;
        for (Nomad.Redeemable redeemable : list) {
            if (slot >= CONTENT_START + CONTENT_SIZE) break;
            List<String> lore = new ArrayList<>(redeemLore(redeemable.material()));
            lore.add("");
            lore.add("§e» Klicken zum Abgeben");
            ItemStack item = named(redeemable.material(), null, lore);
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(Nomad.isHot(redeemable.material()));
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            redeemSlots.put(slot, redeemable.material());
            slot++;
        }
        inventory.setItem(SLOT_HELP, named(Material.BOOK, "§aAnkauf", List.of(
                "§7Diese Items nimmt Nomad immer an.",
                "§7Zwei davon sucht er jeden Tag",
                "§7besonders: die gibt es doppelt.",
                "§7Aufträge bringen deutlich mehr.")));
    }

    private List<String> redeemLore(Material material) {
        List<String> lore = new ArrayList<>();
        if (Nomad.isHot(material)) lore.add("§6★ Heute doppelt gesucht!");
        lore.add("§7Pro Stück: §a+" + Nomad.pointsFor(material) + " Team-Punkte");
        lore.add("§7Du hast: §f" + countPlain(viewer, material));
        return lore;
    }

    private void renderRace() {
        for (int place = 0; place < PODIUM.length; place++) {
            int slot = 20 + place;
            if (place >= standings.size()) {
                inventory.setItem(slot, named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7" + (place + 1) + ". Platz noch frei", List.of()));
                continue;
            }
            Nomad.RaceEntry entry = standings.get(place);
            List<String> lore = new ArrayList<>();
            lore.add("§7Punkte diese Woche: §a" + entry.points());
            if (place < Nomad.RACE_PRIZES.length) lore.add("§7Bonus: §a+" + Nomad.RACE_PRIZES[place] + " Team-Punkte");
            if (entry.teamID().equals(teamID)) lore.add("§a» Dein Team");
            inventory.setItem(slot, named(PODIUM[place], "§6" + (place + 1) + ". §f" + Nomad.teamName(entry.teamID()), lore));
        }
        if (teamID != null) {
            int place = 0;
            long points = 0;
            for (int i = 0; i < standings.size(); i++) {
                if (standings.get(i).teamID().equals(teamID)) {
                    place = i + 1;
                    points = standings.get(i).points();
                }
            }
            inventory.setItem(31, named(Material.PLAYER_HEAD, "§fDein Team", List.of(place == 0
                    ? "§7Noch keine Punkte diese Woche."
                    : "§7Platz §f" + place + " §7mit §a" + points + " §7Punkten.")));
        }
        inventory.setItem(SLOT_HELP, named(Material.CLOCK, "§eWochen-Rennen", List.of(
                "§7Alle Punkte aus Aufträgen und Ankauf",
                "§7von Montag bis Sonntag zählen.",
                "§7Bonus: §61. §a+" + Nomad.RACE_PRIZES[0] + " §8| §62. §a+" + Nomad.RACE_PRIZES[1] + " §8| §63. §a+" + Nomad.RACE_PRIZES[2],
                "§7Endet in: §f" + remaining(Nomad.weekEnd()))));
    }


    /* detail pages */

    private void renderContract() {
        inventory.setItem(SLOT_BACK, named(Material.ARROW, "§f◀ Zurück", List.of("§7zu den Aufträgen")));
        Nomad.Contract contract = Nomad.contract(detailContract);
        if (contract == null) {
            inventory.setItem(SLOT_ACTION, named(Material.BARRIER, "§7Der Auftrag ist abgelaufen.", List.of()));
            return;
        }
        ItemStack info = contractItem(contract);
        ItemMeta meta = info.getItemMeta();
        List<String> lore = contractLore(contract);
        int owned = countPlain(viewer, contract.material());
        lore.add("§7Du hast: §f" + owned);
        meta.lore(Text.lore(lore));
        info.setItemMeta(meta);
        inventory.setItem(SLOT_INFO, info);

        Nomad.Progress state = progress.get(contract.id());
        int missing = contract.required() - (state == null ? 0 : state.delivered());
        if (teamID == null) {
            inventory.setItem(SLOT_ACTION, named(Material.GRAY_STAINED_GLASS_PANE, "§7Nur für Teams", List.of()));
        } else if (state != null && state.completed()) {
            inventory.setItem(SLOT_ACTION, named(Material.GRAY_STAINED_GLASS_PANE, "§aDein Team hat den Auftrag erledigt", List.of()));
        } else if (owned == 0) {
            inventory.setItem(SLOT_ACTION, named(Material.GRAY_STAINED_GLASS_PANE, "§7Du hast nichts davon dabei", List.of()));
        } else {
            int amount = Math.min(owned, missing);
            inventory.setItem(SLOT_ACTION, named(Material.LIME_STAINED_GLASS_PANE, "§a§lAbliefern §8» §f" + amount + "×", List.of(
                    "§7Dein Team braucht noch: §f" + missing,
                    amount >= missing ? "§aDamit ist der Auftrag erledigt!" : "§7Danach fehlen noch: §f" + (missing - amount),
                    "",
                    "§e» Linksklick zum Abliefern")));
            actionEnabled = true;
        }
    }

    private void renderRedeem() {
        inventory.setItem(SLOT_BACK, named(Material.ARROW, "§f◀ Zurück", List.of("§7zum Ankauf")));
        Material material = detailMaterial;
        if (material == null || Nomad.pointsFor(material) <= 0) {
            inventory.setItem(SLOT_ACTION, named(Material.BARRIER, "§7Das nimmt Nomad gerade nicht.", List.of()));
            return;
        }
        inventory.setItem(SLOT_INFO, named(material, null, redeemLore(material)));
        int owned = countPlain(viewer, material);
        if (teamID == null) {
            inventory.setItem(SLOT_ACTION, named(Material.GRAY_STAINED_GLASS_PANE, "§7Nur für Teams", List.of()));
        } else if (owned == 0) {
            inventory.setItem(SLOT_ACTION, named(Material.GRAY_STAINED_GLASS_PANE, "§7Du hast nichts davon dabei", List.of()));
        } else {
            inventory.setItem(SLOT_ACTION, named(Material.LIME_STAINED_GLASS_PANE, "§a§lAlle abgeben §8» §f" + owned + "×", List.of(
                    "§7Dafür bekommt dein Team: §a+" + owned * Nomad.pointsFor(material) + " Team-Punkte",
                    "",
                    "§e» Linksklick zum Abgeben")));
            actionEnabled = true;
        }
    }


    /* clicks (main thread) */

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || busy) return;
        if (System.currentTimeMillis() < ignoreClicksUntil) return;
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
        int slot = event.getRawSlot();

        if (page != Page.OVERVIEW) {
            if (slot == SLOT_BACK) {
                page = Page.OVERVIEW;
                Tasks.sync(this::reopen);
            } else if (slot == SLOT_ACTION && actionEnabled && click == ClickType.LEFT) {
                if (page == Page.CONTRACT) deliver(player); else redeem(player);
            }
            return;
        }
        if (slot >= 0 && slot < Tab.values().length) {
            Tab selected = Tab.values()[slot];
            if (selected == tab) return;
            tab = selected;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            Tasks.sync(this::reopen);
            return;
        }
        if (slot == SLOT_CLOSE) {
            Tasks.sync(player::closeInventory);
            return;
        }
        Long contract = contractSlots.get(slot);
        if (contract != null) {
            page = Page.CONTRACT;
            detailContract = contract;
            Tasks.sync(this::reopen);
            return;
        }
        Material material = redeemSlots.get(slot);
        if (material != null) {
            page = Page.REDEEM;
            detailMaterial = material;
            Tasks.sync(this::reopen);
        }
    }

    private void deliver(Player player) {
        Nomad.Contract contract = Nomad.contract(detailContract);
        String team = teamID;
        if (contract == null || team == null) return;
        Nomad.Progress state = progress.get(contract.id());
        int missing = contract.required() - (state == null ? 0 : state.delivered());
        int offered = Math.min(countPlain(player, contract.material()), missing);
        if (offered <= 0) return;
        takePlain(player, contract.material(), offered);
        busy = true;
        Tasks.async(() -> {
            Nomad.Delivery delivery;
            try {
                delivery = Nomad.deliver(player.getUniqueId(), team, contract.id(), offered);
            } catch (RuntimeException e) {
                MainThread.run(() -> {
                    busy = false;
                    give(player, contract.material(), offered);
                    fail(player, "§fNomad ist gerade nicht ansprechbar, versuch es gleich nochmal.");
                });
                return;
            }
            Map<Long, Nomad.Progress> newProgress = Nomad.progress(team);
            int points = TeamRepository.getTeamPoints(team);
            MainThread.run(() -> {
                busy = false;
                progress = newProgress;
                teamPoints = points;
                if (offered > delivery.accepted()) give(player, contract.material(), offered - delivery.accepted());
                switch (delivery.outcome()) {
                    case OK -> {
                        if (delivery.completed()) {
                            Component done = Text.section(Nomad.PREFIX + "§a" + Text.strip(Nomad.teamName(team)) + " §fhat den Auftrag §e"
                                    + contract.required() + "× ").append(Component.translatable(contract.material().translationKey()))
                                    .append(Text.section(" §ferledigt! §a+" + delivery.points() + " Team-Punkte"));
                            notifyTeam(team, done);
                            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
                        } else {
                            player.sendMessage(Nomad.PREFIX + "Danke! Ihr habt jetzt §e" + delivery.delivered() + "§7/§e"
                                    + contract.required() + "§f, es fehlen noch §e" + (contract.required() - delivery.delivered()) + "§f.");
                            player.playSound(player.getLocation(), Sound.ENTITY_WANDERING_TRADER_YES, 1f, 1f);
                        }
                    }
                    case NOT_ACTIVE -> fail(player, "§fDer Auftrag ist leider gerade abgelaufen.");
                    case ALREADY_DONE -> fail(player, "§fDein Team hat den Auftrag schon erledigt.");
                    case NO_TEAM -> fail(player, "§fNur Teams können Aufträge erfüllen.");
                }
                render();
            });
        });
    }

    private void redeem(Player player) {
        Material material = detailMaterial;
        String team = teamID;
        if (material == null || team == null) return;
        int quantity = countPlain(player, material);
        if (quantity <= 0) return;
        takePlain(player, material, quantity);
        busy = true;
        Tasks.async(() -> {
            int points;
            try {
                points = Nomad.redeem(player.getUniqueId(), team, material, quantity);
            } catch (RuntimeException e) {
                points = 0;
            }
            int earned = points;
            int total = TeamRepository.getTeamPoints(team);
            MainThread.run(() -> {
                busy = false;
                teamPoints = total;
                if (earned <= 0) {
                    give(player, material, quantity);
                    fail(player, "§fNomad nimmt das gerade nicht an.");
                } else {
                    notifyTeam(team, Text.section(Nomad.PREFIX + "§e" + player.getName() + " §fhat §e" + quantity + "× ")
                            .append(Component.translatable(material.translationKey()))
                            .append(Text.section(" §fabgegeben. §a+" + earned + " Team-Punkte")));
                    player.playSound(player.getLocation(), Sound.ENTITY_WANDERING_TRADER_YES, 1f, 1f);
                }
                render();
            });
        });
    }

    private static void notifyTeam(String teamID, Component message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (teamID.equals(CacheHandler.getInstance().getPlayerInCache(online).getTeamID())) online.sendMessage(message);
        }
    }


    /* helpers */

    private static String bar(int value, int max) {
        int filled = max <= 0 ? 0 : (int) Math.round(10.0 * Math.min(value, max) / max);
        return "§a" + "■".repeat(filled) + "§7" + "■".repeat(10 - filled);
    }

    private static String remaining(Instant end) {
        Duration left = Duration.between(Instant.now(), end);
        if (left.isNegative()) return "gleich";
        long days = left.toDays();
        if (days >= 1) return days + (days == 1 ? " Tag " : " Tage ") + left.toHoursPart() + " Std.";
        if (left.toHours() >= 1) return left.toHours() + " Std. " + left.toMinutesPart() + " Min.";
        return Math.max(1, left.toMinutes()) + " Min.";
    }

}
