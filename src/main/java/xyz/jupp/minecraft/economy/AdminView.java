package xyz.jupp.minecraft.economy;

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
import xyz.jupp.minecraft.database.SeasonReset;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static xyz.jupp.minecraft.economy.ShopView.named;
import static xyz.jupp.minecraft.economy.ShopView.pane;
import static xyz.jupp.minecraft.economy.ShopView.percent;

/**
 * /wirtschaft, admins only: the economy at a glance, the switches of {@link AdminSettings}, emergency stops and three
 * monitoring pages (players online, what stands out in the last 24 hours, where the money flowed). Left click lowers
 * or switches back, right click raises or switches on, shift + left click puts a setting back to its default.
 * Players never see these decisions as such (no dates, no "an admin closed it", only "closed" or "not in stock").
 * Every database read runs on a worker; the inventory is only touched on the main thread.
 */
public final class AdminView implements InventoryHolder {

    private enum Page { MAIN, PLAYERS, ALERTS, FLOWS }

    private static final int SIZE = 54;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_CLOSE = 8;
    // main page: settings
    private static final int SLOT_SETTINGS_LABEL = 9;
    private static final int SLOT_NETHERITE = 10;
    private static final int SLOT_NETHERITE_LIMIT = 11;
    private static final int SLOT_DIAMOND = 12;
    private static final int SLOT_WITHDRAW = 13;
    private static final int SLOT_DEPOSIT = 14;
    private static final int SLOT_SATURATION = 15;
    private static final int SLOT_LOANS = 16;
    private static final int SLOT_AI = 17;
    private static final int SLOT_SELL_STEP = 19;
    // main page: emergency stops
    private static final int SLOT_STOP_LABEL = 27;
    private static final Map<Integer, String> STOP_SLOTS = Map.of(
            28, AdminSettings.STOP_SHOP_SELL, 29, AdminSettings.STOP_SHOP_BUY, 30, AdminSettings.STOP_CHEST_SHOPS,
            31, AdminSettings.STOP_HONDO, 32, AdminSettings.STOP_BAUAMT, 33, AdminSettings.STOP_CASH);
    // main page: monitoring
    private static final int SLOT_MONITOR_LABEL = 45;
    private static final int SLOT_PLAYERS = 47;
    private static final int SLOT_ALERTS = 49;
    private static final int SLOT_FLOWS = 51;
    // sub pages: the heads between the top row and the back button
    private static final int SLOT_BACK = 45;
    private static final int HEADS_FIRST = 9;
    private static final int HEADS_LAST = 44;
    private static final double TAX_STEP = 0.01;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(Market.ZONE);

    private final Inventory inventory;
    private Page page = Page.MAIN;
    private Info info;
    private List<PlayerRow> players = List.of();
    private @Nullable Alerts alerts;
    private @Nullable Flows flows;
    // player head slot -> player (players page)
    private final Map<Integer, UUID> headSlots = new HashMap<>();
    private boolean busy;

    private record Info(long free, long brokeBelow, long recoveredAt, @Nullable String resetPending, long accounts,
                        long loansOpen, long loanCount, long debt, long bondsOwed, long depositsOwed) {}

    private record PlayerRow(UUID uuid, long money, boolean wanted, boolean jail, long shopSold, long chestSold, long fromState,
                             long loan, long debt) {}

    private record Ranked(UUID uuid, long amount) {}

    private record ChestPair(UUID seller, UUID buyer, long sum, long count) {}

    private record Dumped(String material, long net, long quantity) {}

    private record Alerts(List<Ranked> income, List<Ranked> richest, List<ChestPair> chestPairs, List<Dumped> dumped) {}

    private record Flows(long minted, long destroyed, Map<Treasury.Source, Long> treasury, long accounts, List<long[]> supply) {}

    private AdminView(Info info) {
        this.info = info;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.section("§6Wirtschaft §8» §7Admin"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static void open(@NotNull Player player) {
        Tasks.supplyAsync(AdminView::info, info -> {
            if (!player.isOnline()) return;
            AdminView view = new AdminView(info);
            view.render();
            player.openInventory(view.inventory);
        });
    }


    /* data (worker) */

    private static Info info() {
        Bankruptcy.Level level = Bankruptcy.level();
        long[] row = Database.queryOne("""
                SELECT (SELECT COALESCE(SUM(money), 0) FROM players),
                       (SELECT COALESCE(SUM(principal), 0) FROM bank_loans WHERE state = 'OPEN'),
                       (SELECT COUNT(*) FROM bank_loans WHERE state = 'OPEN'),
                       (SELECT COALESCE(SUM(GREATEST(0, locked - paid)), 0) FROM bank_loans WHERE state = 'DEFAULTED'),
                       (SELECT COALESCE(SUM(payout), 0) FROM state_bonds WHERE NOT repaid),
                       (SELECT COALESCE(SUM(amount), 0) FROM bank_deposits WHERE NOT paid_out)""",
                r -> new long[]{r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6)});
        long[] v = row == null ? new long[6] : row;
        return new Info(level.free(), level.brokeBelow(), level.recoveredAt(), SeasonReset.pending(), v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    private static List<PlayerRow> players(List<UUID> online) {
        if (online.isEmpty()) return List.of();
        return Database.withConnection(connection -> Database.query(connection, """
                SELECT p.uuid, p.money, p.is_wanted, p.jail,
                       (SELECT COALESCE(SUM(net), 0) FROM market_transactions m WHERE m.player_uuid = p.uuid AND m.kind = 'SELL'
                            AND m.moves_money AND m.created_at >= now() - interval '24 hours'),
                       (SELECT COALESCE(SUM(price), 0) FROM chest_shop_sales c WHERE c.seller_uuid = p.uuid
                            AND c.created_at >= now() - interval '24 hours')
                       - (SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger t WHERE t.player_uuid = p.uuid
                            AND t.source = 'CHEST_SHOP_TAX' AND t.created_at >= now() - interval '24 hours'),
                       (SELECT COALESCE(-SUM(amount), 0) FROM treasury_ledger t WHERE t.player_uuid = p.uuid AND t.amount < 0
                            AND t.source IN ('BUILDING_GRANT', 'BOUNTY', 'INTEREST')
                            AND t.created_at >= now() - interval '24 hours'),
                       (SELECT COALESCE(SUM(principal), 0) FROM bank_loans l WHERE l.player_uuid = p.uuid AND l.state = 'OPEN'),
                       (SELECT COALESCE(SUM(GREATEST(0, locked - paid)), 0) FROM bank_loans l WHERE l.player_uuid = p.uuid
                            AND l.state = 'DEFAULTED')
                FROM players p WHERE p.uuid = ANY(?) ORDER BY p.money DESC""",
                r -> new PlayerRow(r.getObject(1, UUID.class), r.getLong(2), r.getBoolean(3), r.getBoolean(4), r.getLong(5),
                        r.getLong(6), r.getLong(7), r.getLong(8), r.getLong(9)),
                connection.createArrayOf("uuid", online.toArray())));
    }

    private static Alerts alerts() {
        List<Ranked> income = Database.query("""
                SELECT uuid, SUM(amount) AS total FROM (
                    SELECT player_uuid AS uuid, net AS amount FROM market_transactions
                        WHERE kind = 'SELL' AND moves_money AND created_at >= now() - interval '24 hours'
                    UNION ALL SELECT seller_uuid, price FROM chest_shop_sales WHERE created_at >= now() - interval '24 hours'
                    UNION ALL SELECT player_uuid, -amount FROM treasury_ledger WHERE player_uuid IS NOT NULL
                        AND source = 'CHEST_SHOP_TAX' AND created_at >= now() - interval '24 hours'
                    UNION ALL SELECT player_uuid, -amount FROM treasury_ledger WHERE amount < 0 AND player_uuid IS NOT NULL
                        AND source IN ('BUILDING_GRANT', 'BOUNTY', 'INTEREST') AND created_at >= now() - interval '24 hours'
                ) s GROUP BY uuid ORDER BY total DESC LIMIT 7""", r -> new Ranked(r.getObject(1, UUID.class), r.getLong(2)));
        List<Ranked> richest = Database.query("SELECT uuid, money FROM players ORDER BY money DESC LIMIT 7",
                r -> new Ranked(r.getObject(1, UUID.class), r.getLong(2)));
        List<ChestPair> pairs = Database.query("""
                SELECT seller_uuid, buyer_uuid, SUM(price), COUNT(*) FROM chest_shop_sales WHERE created_at >= now() - interval '24 hours'
                GROUP BY seller_uuid, buyer_uuid ORDER BY 3 DESC LIMIT 7""",
                r -> new ChestPair(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3), r.getLong(4)));
        List<Dumped> dumped = Database.query("""
                SELECT material, SUM(net), SUM(quantity) FROM market_transactions WHERE kind = 'SELL' AND moves_money
                    AND created_at >= now() - interval '24 hours'
                GROUP BY material ORDER BY 2 DESC LIMIT 7""", r -> new Dumped(r.getString(1), r.getLong(2), r.getLong(3)));
        return new Alerts(income, richest, pairs, dumped);
    }

    private static Flows flows() {
        long[] shop = Database.queryOne("""
                SELECT (SELECT COALESCE(SUM(net), 0) FROM market_transactions WHERE kind = 'SELL' AND moves_money
                            AND created_at >= now() - interval '24 hours'),
                       (SELECT COALESCE(SUM(net), 0) FROM market_transactions WHERE kind = 'BUY' AND moves_money
                            AND created_at >= now() - interval '24 hours')
                       - (SELECT COALESCE(SUM(amount), 0) FROM treasury_ledger WHERE source = 'EMERGENCY_SALE'
                            AND created_at >= now() - interval '24 hours'),
                       (SELECT COALESCE(SUM(money), 0) FROM players)""",
                r -> new long[]{r.getLong(1), r.getLong(2), r.getLong(3)});
        long[] v = shop == null ? new long[3] : shop;
        Map<Treasury.Source, Long> treasury = Treasury.inflowSince(Instant.now().minusSeconds(24 * 3600));
        List<long[]> supply = Database.query("SELECT day, money_supply FROM economy_snapshots ORDER BY day DESC LIMIT 3",
                r -> new long[]{r.getDate(1).toLocalDate().toEpochDay(), r.getLong(2)});
        return new Flows(v[0], v[1], treasury, v[2], supply);
    }

    // the last bookings of one player, for the chat: Basil's statement (bank, trader, taxes)
    private static List<String> statement(UUID player) {
        return Bank.statement(player, 10).stream().map(entry -> "§7" + TIME.format(entry.at()) + " §f" + entry.kind() + " "
                + (entry.amount() >= 0 ? "§a+" : "§c") + Items.format(entry.amount())
                + (entry.note() == null || entry.note().isBlank() ? "" : " §7(" + entry.note() + ")")).toList();
    }


    /* render (main thread) */

    private void render() {
        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);
        headSlots.clear();
        inventory.setItem(SLOT_INFO, infoItem());
        inventory.setItem(SLOT_CLOSE, named(Material.BARRIER, "§cSchließen", List.of()));
        switch (page) {
            case MAIN -> renderMain();
            case PLAYERS -> renderPlayers();
            case ALERTS -> renderAlerts();
            case FLOWS -> renderFlows();
        }
    }

    private void renderMain() {
        inventory.setItem(SLOT_SETTINGS_LABEL, named(Material.YELLOW_STAINED_GLASS_PANE, "§eEinstellungen §7▶", List.of()));
        String modeText = switch (AdminSettings.netheriteMode()) {
            case AUTO -> "§eautomatisch §7(ab Tag " + AdminSettings.NETHERITE_AUTO_DAY + ")";
            case OPEN -> "§afreigegeben";
            case CLOSED -> "§cgesperrt";
        };
        inventory.setItem(SLOT_NETHERITE, setting(Material.NETHERITE_INGOT, "§fNetherit bei Hondo: " + modeText, List.of(
                "§7Gerade: " + (Hondo.netheriteOpen() ? "§azu kaufen" : "§cnicht zu kaufen"),
                "§7Spieler sehen kein Datum, nur \"gerade nicht vorrätig\".",
                "§7Gilt auch für das Freundschaftsangebot (Stufe 5).",
                "§7Frühestens, wenn der Nether offen ist.",
                "§7Beim Saisonreset wieder automatisch."), "umschalten"));
        inventory.setItem(SLOT_NETHERITE_LIMIT, setting(Material.CLOCK, "§fNetherit pro 24 Stunden: §a" + AdminSettings.netheritePerDay(),
                List.of("§7Je Spieler, gekauft und als Angebot zusammen."), "-1 / +1"));
        inventory.setItem(SLOT_DIAMOND, setting(Material.DIAMOND, "§fDiamanten zum Saisonstart teurer: "
                        + (AdminSettings.diamondEarly() ? "§aan" : "§caus"), List.of(
                "§7Die ersten " + Hondo.DIAMOND_EARLY_DAYS + " Tage der Saison " + percent(Hondo.DIAMOND_EARLY_FACTOR - 1)
                        + " teurer bei Hondo.",
                "§7Gerade: " + (Hondo.earlyFactor(Material.DIAMOND) > 1 ? "§eAufschlag aktiv" : "§7kein Aufschlag")), "an / aus"));
        inventory.setItem(SLOT_WITHDRAW, setting(Material.PAPER, "§fSteuer Bargeld abheben: §a" + percent(AdminSettings.cashWithdrawTax()),
                taxLore(Taxes.cashWithdrawRate(), "§7Gilt auch für Verkäufe in Kistenshops."), "-1 % / +1 %"));
        inventory.setItem(SLOT_DEPOSIT, setting(Material.CHEST, "§fSteuer Bargeld einzahlen: §a" + percent(AdminSettings.cashDepositTax()),
                taxLore(Taxes.cashDepositRate(), null), "-1 % / +1 %"));
        inventory.setItem(SLOT_SATURATION, setting(Material.HOPPER, "§fHändler gesättigt: §a" + hours(AdminSettings.saturatedHours()) + " Stunden",
                List.of("§7Fällt der Ankauf einer Ware auf 0, kauft", "§7der Händler sie so lange nicht an."), "-1 / +1 Stunde"));
        inventory.setItem(SLOT_LOANS, setting(Material.EMERALD, "§fNeue Kredite bei Basil: " + (AdminSettings.loansOpen() ? "§aja" : "§cnein"),
                List.of("§7Laufende Kredite laufen weiter.", "§7Sinnvoll z. B. vor einem Saisonende",
                        "§7(ein vorgemerkter Saisonreset sperrt sie ohnehin).", "§7Beim Saisonreset wieder an."), "an / aus"));
        inventory.setItem(SLOT_SELL_STEP, setting(Material.ANVIL, "§fPreisverfall beim Verkauf: §a×" + factor(AdminSettings.sellStep()),
                List.of("§7Wie stark ein verkauftes Paket den Ankaufpreis drückt,",
                        "§7verglichen damit, wie stark ein gekauftes ihn hebt.",
                        "§7×1: gleich stark, ×2: doppelt so schnell fallend."), "-0,25 / +0,25"));
        inventory.setItem(SLOT_AI, setting(Material.WRITABLE_BOOK, "§fKI-Marktprüfung: " + (AdminSettings.aiReview() ? "§aan" : "§caus"),
                List.of("§7Täglicher Marktbericht und Korrekturen am Katalog", "§7(braucht den API-Schlüssel in der Config)."), "an / aus"));

        inventory.setItem(SLOT_STOP_LABEL, named(Material.RED_STAINED_GLASS_PANE, "§cNotfall-Schalter §7▶", List.of(
                "§7Für einen Fehler oder Exploit, bis er behoben ist.",
                "§7Spieler sehen nur \"gerade geschlossen\".")));
        stop(28, Material.HOPPER_MINECART, "Händler kauft an", "Spieler verkaufen an den Händler (Geld entsteht).");
        stop(29, Material.LIME_DYE, "Händler verkauft", "Kaufen beim Händler, Zufallsitem und Dienste.");
        stop(30, Material.CHEST, "Kistenshops", "Käufe in den Kistenshops der Spieler.");
        stop(31, Material.AMETHYST_SHARD, "Hondo", "Käufe und Freundschaftsangebote bei Hondo.");
        stop(32, Material.BRICKS, "Bauamt-Förderung", "Keine neuen Aufträge, keine Auszahlungen.");
        stop(33, Material.PAPER, "Bargeld bei Basil", "Abheben und Einzahlen von Bargeld.");

        inventory.setItem(SLOT_MONITOR_LABEL, named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "§bÜberblick §7▶", List.of()));
        inventory.setItem(SLOT_PLAYERS, named(Material.PLAYER_HEAD, "§bSpieler online", List.of(
                "§7Konto, Kredit, Schulden, Einnahmen der letzten",
                "§724 Stunden; Klick zeigt die letzten Buchungen.", "", "§e» Klick: öffnen")));
        inventory.setItem(SLOT_ALERTS, named(Material.SPYGLASS, "§bAuffälligkeiten (24 h)", List.of(
                "§7Wer am meisten eingenommen hat, die reichsten",
                "§7Konten, große Kistenshop-Geschäfte, was in", "§7Massen an den Händler ging.", "", "§e» Klick: öffnen")));
        inventory.setItem(SLOT_FLOWS, named(Material.GOLD_NUGGET, "§bGeldflüsse (24 h)", List.of(
                "§7Wie viel Geld entstanden und verschwunden ist,", "§7die Staatskasse nach Quellen.", "", "§e» Klick: öffnen")));
    }

    private void stop(int slot, Material icon, String what, String detail) {
        boolean stopped = AdminSettings.stopped(STOP_SLOTS.get(slot));
        inventory.setItem(slot, named(stopped ? Material.BARRIER : icon, (stopped ? "§c§lGESTOPPT: §f" : "§a") + what, List.of(
                "§7" + detail, "", stopped ? "§e» Klick: wieder freigeben" : "§e» Shift + Klick: stoppen")));
    }

    private void renderPlayers() {
        inventory.setItem(SLOT_BACK, named(Material.ARROW, "§7Zurück", List.of()));
        if (players.isEmpty()) {
            inventory.setItem(22, named(Material.GRAY_DYE, "§7Niemand online", List.of()));
            return;
        }
        for (int i = 0; i < players.size() && HEADS_FIRST + i <= HEADS_LAST; i++) {
            PlayerRow row = players.get(i);
            int slot = HEADS_FIRST + i;
            headSlots.put(slot, row.uuid());
            List<String> lore = new ArrayList<>();
            lore.add("§7Konto: §f" + Items.format(row.money()));
            if (row.loan() > 0) lore.add("§7Offener Kredit: §f" + Items.format(row.loan()));
            if (row.debt() > 0) lore.add("§cSchulden: " + Items.format(row.debt()));
            lore.add("§7Letzte 24 h: Händler §f" + Items.format(row.shopSold()) + "§7, Kistenshop §f" + Items.format(row.chestSold())
                    + "§7, vom Staat §f" + Items.format(row.fromState()));
            if (row.jail()) lore.add("§cIm Gefängnis");
            else if (row.wanted()) lore.add("§cGesucht");
            lore.add("");
            lore.add("§e» Klick: letzte Buchungen im Chat");
            ItemStack head = named(Material.PLAYER_HEAD, "§f" + name(row.uuid()), lore);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                OfflinePlayer owner = Bukkit.getOfflinePlayer(row.uuid());
                meta.setOwningPlayer(owner);
                head.setItemMeta(meta);
            }
            inventory.setItem(slot, head);
        }
    }

    private void renderAlerts() {
        inventory.setItem(SLOT_BACK, named(Material.ARROW, "§7Zurück", List.of()));
        if (alerts == null) return;
        List<String> income = new ArrayList<>(List.of("§7Händler-Ankauf + Kistenshop (nach Steuer)", "§7+ Förderung, Kopfgeld, Zinsen", ""));
        alerts.income().forEach(r -> income.add("§f" + name(r.uuid()) + " §7» §a" + Items.format(r.amount())));
        if (alerts.income().isEmpty()) income.add("§7Nichts.");
        inventory.setItem(20, named(Material.GOLD_INGOT, "§eMeiste Einnahmen", income));

        List<String> richest = new ArrayList<>(List.of("§7Kontostand jetzt", ""));
        alerts.richest().forEach(r -> richest.add("§f" + name(r.uuid()) + " §7» §a" + Items.format(r.amount())));
        if (alerts.richest().isEmpty()) richest.add("§7Nichts.");
        inventory.setItem(22, named(Material.EMERALD_BLOCK, "§eReichste Konten", richest));

        List<String> pairs = new ArrayList<>(List.of("§7Verkäufer ← Käufer, Summe (Anzahl).", "§7Viel Geld zwischen denselben zwei",
                "§7Spielern kann eine Verschiebung sein.", ""));
        alerts.chestPairs().forEach(p -> pairs.add("§f" + name(p.seller()) + " §7← §f" + name(p.buyer()) + " §7» §a"
                + Items.format(p.sum()) + " §7(" + p.count() + "×)"));
        if (alerts.chestPairs().isEmpty()) pairs.add("§7Nichts.");
        inventory.setItem(24, named(Material.CHEST, "§eKistenshop-Geschäfte", pairs));

        List<String> dumped = new ArrayList<>(List.of("§7Was an den Händler verkauft wurde", "§7(so entsteht Geld).", ""));
        alerts.dumped().forEach(d -> dumped.add("§f" + d.material() + " §7» §a" + Items.format(d.net()) + " §7für "
                + Items.format(d.quantity()) + " Stück"));
        if (alerts.dumped().isEmpty()) dumped.add("§7Nichts.");
        inventory.setItem(31, named(Material.HOPPER, "§eAnkauf nach Ware", dumped));
    }

    private void renderFlows() {
        inventory.setItem(SLOT_BACK, named(Material.ARROW, "§7Zurück", List.of()));
        if (flows == null) return;
        inventory.setItem(20, named(Material.LIME_DYE, "§aGeld entstanden: " + Items.format(flows.minted()), List.of(
                "§7Was der Händler in 24 h angekauft hat.", "§7Dazu kommt, was der Staat auszahlt (rechts).")));
        inventory.setItem(22, named(Material.RED_DYE, "§cGeld verschwunden: " + Items.format(flows.destroyed()), List.of(
                "§7Was beim Händler in 24 h gekauft wurde.", "§7Dazu kommen die Steuern (rechts).")));
        List<String> treasury = new ArrayList<>(List.of("§7Netto je Quelle, + rein, - raus", ""));
        long total = 0;
        for (Map.Entry<Treasury.Source, Long> entry : flows.treasury().entrySet()) {
            long amount = entry.getValue();
            total += amount;
            treasury.add("§f" + entry.getKey().label() + " §7» " + (amount >= 0 ? "§a+" : "§c") + Items.format(amount));
        }
        if (flows.treasury().isEmpty()) treasury.add("§7Keine Buchungen.");
        treasury.add("");
        treasury.add("§7Zusammen: " + (total >= 0 ? "§a+" : "§c") + Items.format(total));
        inventory.setItem(24, named(Material.GOLD_BLOCK, "§eStaatskasse (24 h)", treasury));
        List<String> supply = new ArrayList<>(List.of("§7Auf allen Konten jetzt: §f" + Items.format(flows.accounts()), "",
                "§7Geld der Spieler laut Tagesmessung", "§7(Konten, Festgeld, Anleihen, Teams, minus Kredite):"));
        for (long[] day : flows.supply()) {
            supply.add("§7" + LocalDate.ofEpochDay(day[0]).format(DateTimeFormatter.ofPattern("dd.MM.")) + " §f" + Items.format(day[1]));
        }
        if (flows.supply().isEmpty()) supply.add("§7Noch keine Messung.");
        inventory.setItem(31, named(Material.BOOK, "§eGeldmenge", supply));
    }

    private ItemStack infoItem() {
        List<String> lore = new ArrayList<>();
        lore.add("§7Saison-Tag: §f" + SeasonReset.seasonDay() + " §7(seit " + SeasonReset.startDay() + ")");
        lore.add("§7Staatskasse frei: §f" + Items.format(Math.max(0, info.free())) + " Schilling");
        lore.add("§7Pleite unter §f" + Items.format(info.brokeBelow()) + "§7, erholt ab §f" + Items.format(info.recoveredAt()));
        lore.add("§7Staat: " + (Bankruptcy.isBroke() ? "§cpleite" : "§azahlungsfähig"));
        lore.add("§7Steuerfaktor: §f" + String.format(Locale.GERMANY, "%.2f", Economy.factor()) + " §7(" + Text.strip(Economy.levelWord()) + ")");
        lore.add("§7Auf allen Konten: §f" + Items.format(info.accounts()));
        lore.add("§7Kredite offen: §f" + info.loanCount() + " §7über §f" + Items.format(info.loansOpen())
                + (info.debt() > 0 ? " §8| §cSchulden " + Items.format(info.debt()) : ""));
        lore.add("§7Festgeld: §f" + Items.format(info.depositsOwed()) + " §8| §7Anleihen: §f" + Items.format(info.bondsOwed()));
        lore.add("§7Saisonreset: " + (info.resetPending() == null ? "§fnicht vorgemerkt" : "§cvorgemerkt §7(" + info.resetPending() + ")"));
        long stops = STOP_SLOTS.values().stream().filter(AdminSettings::stopped).count();
        if (stops > 0) lore.add("§c§l" + stops + " Notfall-Schalter aktiv");
        lore.add("");
        lore.add("§e» Shift + Rechts: §fSaisonstart auf heute setzen");
        lore.add("§7(z. B. am Eröffnungstag, wenn der Server vorher");
        lore.add("§7schon zum Einrichten lief)");
        return named(Material.GOLD_INGOT, "§6Wirtschaft", lore);
    }

    private static List<String> taxLore(double effective, @Nullable String extra) {
        List<String> lore = new ArrayList<>(List.of("§7Grundwert mal Steuerfaktor, gerade §f" + percent(effective) + "§7.",
                "§7Steigt und fällt mit der Wirtschaft."));
        if (extra != null) lore.add(extra);
        return lore;
    }

    private static ItemStack setting(Material icon, String name, List<String> description, String controls) {
        List<String> lore = new ArrayList<>(description);
        lore.add("");
        lore.add("§e» Links / Rechts: §f" + controls);
        lore.add("§e» Shift + Links: §fStandard");
        return named(icon, name, lore);
    }

    private static String factor(double value) {
        return String.format(Locale.GERMANY, "%.2f", value);
    }

    private static String hours(double hours) {
        return hours == Math.rint(hours) ? String.valueOf((long) hours) : String.format(Locale.GERMANY, "%.1f", hours);
    }

    private static String name(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }


    /* clicks (main thread) */

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || busy) return;
        if (!PermissionsUtil.isPlayerAdmin(player)) {
            player.closeInventory();
            return;
        }
        ClickType click = event.getClick();
        int slot = event.getRawSlot();
        if (slot == SLOT_CLOSE) {
            Tasks.sync(player::closeInventory);
            return;
        }
        // the season begins today (opening day after a setup phase)
        if (slot == SLOT_INFO && click == ClickType.SHIFT_RIGHT) {
            load(player, () -> {
                SeasonReset.startToday(player.getName());
                return info();
            }, fresh -> {
                info = fresh;
                player.sendMessage(Main.getChatPrefix() + "§fDie Saison beginnt jetzt heute §7(Tag 1)§f.");
                HondoView.refreshAll();
            });
            return;
        }
        if (page != Page.MAIN) {
            if (slot == SLOT_BACK) {
                load(player, AdminView::info, fresh -> {
                    info = fresh;
                    page = Page.MAIN;
                });
                return;
            }
            UUID target = page == Page.PLAYERS ? headSlots.get(slot) : null;
            if (target != null) {
                String who = name(target);
                Tasks.supplyAsync(() -> statement(target), lines -> {
                    player.sendMessage(Main.getChatPrefix() + "§fLetzte Buchungen von §e" + who + "§f:");
                    if (lines.isEmpty()) player.sendMessage("§7Keine.");
                    lines.forEach(player::sendMessage);
                });
            }
            return;
        }
        if (slot == SLOT_PLAYERS) {
            List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
            load(player, () -> players(online), rows -> {
                players = rows;
                page = Page.PLAYERS;
            });
            return;
        }
        if (slot == SLOT_ALERTS) {
            load(player, AdminView::alerts, result -> {
                alerts = result;
                page = Page.ALERTS;
            });
            return;
        }
        if (slot == SLOT_FLOWS) {
            load(player, AdminView::flows, result -> {
                flows = result;
                page = Page.FLOWS;
            });
            return;
        }
        String stopKey = STOP_SLOTS.get(slot);
        if (stopKey != null) {
            boolean stopped = AdminSettings.stopped(stopKey);
            // stopping needs a shift click (no accident), releasing a plain one
            if (!stopped && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) return;
            store(player, stopKey, stopped ? null : "true");
            return;
        }
        boolean reset = click == ClickType.SHIFT_LEFT;
        boolean up = click == ClickType.RIGHT;
        if (!reset && click != ClickType.LEFT && !up) return;
        String key;
        String value;
        switch (slot) {
            case SLOT_NETHERITE -> {
                key = AdminSettings.NETHERITE_MODE;
                AdminSettings.NetheriteMode[] modes = AdminSettings.NetheriteMode.values();
                int next = Math.floorMod(AdminSettings.netheriteMode().ordinal() + (up ? 1 : -1), modes.length);
                value = modes[next] == AdminSettings.NetheriteMode.AUTO ? null : modes[next].name();
            }
            case SLOT_NETHERITE_LIMIT -> {
                key = AdminSettings.NETHERITE_PER_DAY;
                value = String.valueOf(Math.max(AdminSettings.NETHERITE_PER_DAY_DEFAULT, Math.min(16, AdminSettings.netheritePerDay() + (up ? 1 : -1))));
            }
            case SLOT_DIAMOND -> {
                key = AdminSettings.DIAMOND_EARLY;
                value = AdminSettings.diamondEarly() ? "false" : null;
            }
            case SLOT_WITHDRAW -> {
                key = AdminSettings.CASH_WITHDRAW_TAX;
                value = tax(AdminSettings.cashWithdrawTax(), up);
            }
            case SLOT_DEPOSIT -> {
                key = AdminSettings.CASH_DEPOSIT_TAX;
                value = tax(AdminSettings.cashDepositTax(), up);
            }
            case SLOT_SATURATION -> {
                key = AdminSettings.SATURATED_HOURS;
                value = String.valueOf(Math.max(1, Math.min(72, Math.round(AdminSettings.saturatedHours()) + (up ? 1 : -1))));
            }
            case SLOT_LOANS -> {
                key = AdminSettings.LOANS_OPEN;
                value = AdminSettings.loansOpen() ? "false" : null;
            }
            case SLOT_SELL_STEP -> {
                key = AdminSettings.SELL_STEP;
                double next = Math.round((AdminSettings.sellStep() + (up ? 0.25 : -0.25)) * 4) / 4.0;
                value = String.valueOf(Math.max(1, Math.min(3, next)));
            }
            case SLOT_AI -> {
                key = AdminSettings.AI_REVIEW;
                value = AdminSettings.aiReview() ? "false" : "true";
            }
            default -> {
                return;
            }
        }
        store(player, key, reset ? null : value);
    }

    // stores a setting on a worker, then shows the menu again
    private void store(Player player, String key, @Nullable String value) {
        load(player, () -> {
            AdminSettings.set(key, value, player.getName());
            return info();
        }, fresh -> {
            info = fresh;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            // the counters and menus show the new rules right away
            HondoView.refreshAll();
            ShopView.refreshAll();
        });
    }

    // runs the work on a worker; on the main thread applies the result and renders (a failure leaves everything as it was)
    private <T> void load(Player player, Supplier<T> work, Consumer<T> apply) {
        busy = true;
        Tasks.supplyAsync(() -> {
            try {
                return Optional.ofNullable(work.get());
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("admin menu: {}", e.toString());
                return null;
            }
        }, result -> {
            busy = false;
            if (result == null) {
                ShopView.fail(player, "§fDas ging gerade nicht, versuch es gleich nochmal.");
                return;
            }
            result.ifPresent(apply);
            if (player.getOpenInventory().getTopInventory() == inventory) render();
        });
    }

    // whole percent steps between 0 and 50 %
    private static String tax(double current, boolean up) {
        double next = Math.round((current + (up ? TAX_STEP : -TAX_STEP)) * 100) / 100.0;
        return String.valueOf(Math.max(0, Math.min(0.5, next)));
    }

}
