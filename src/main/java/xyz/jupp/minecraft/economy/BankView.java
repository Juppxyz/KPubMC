package xyz.jupp.minecraft.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.utils.PlayerSaves;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.database.BankLog;
import xyz.jupp.minecraft.database.ChestShopRepository;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.CombatLock;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static xyz.jupp.minecraft.economy.ShopView.fail;
import static xyz.jupp.minecraft.economy.ShopView.named;
import static xyz.jupp.minecraft.economy.ShopView.pane;

/**
 * Basil's counter: account, cash in and out, fixed deposits, the vault and the statement (rules in {@link Bank}).
 * Cash is taken from the inventory on the main thread before the booking and given back if it fails.
 */
public final class BankView implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int SLOT_BALANCE = 4;
    private static final int SLOT_CLOSE = 8;
    private static final int SLOT_DEPOSIT = 10;
    private static final int WITHDRAW_START = 12;
    private static final int SLOT_TERM_INFO = 18;
    private static final int TERM_START = 19;
    private static final int RUNNING_START = 23;
    private static final int SLOT_STATEMENT = 29;
    private static final int SLOT_VAULT = 31;
    private static final int SLOT_BONDS = 33;
    private static final int STATEMENT_LINES = 10;
    // loans: info, the amounts (or the open loan and its button)
    private static final int SLOT_LOAN_INFO = 36;
    private static final int LOAN_START = 38;
    private static final int SLOT_LOAN_STATUS = 38;
    private static final int SLOT_LOAN_ACTION = 42;
    // the rules page before a loan
    private static final int SLOT_RULES = 13;
    private static final int SLOT_CONFIRM = 29;
    private static final int SLOT_CANCEL = 33;
    private static final long CLICK_COOLDOWN_MILLIS = 300;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(Market.ZONE);

    private final Player viewer;
    private final Inventory inventory;
    private State state;
    private boolean busy;
    // 0: the counter, otherwise the loan whose rules are shown
    private int confirmLoan;
    private long ignoreClicksUntil;

    private record State(int balance, List<Bank.Term> terms, List<Bank.Entry> statement, List<Bank.Payout> payouts, Bank.Offer offer,
                         Loans.@Nullable Loan loan, double loanRate, Loans.Limit loanLimit, Bonds.Holding bonds, Map<String, String> names) {}

    // playtime and first join, read on the main thread (the loan limit depends on them)
    private volatile Loans.Standing standing;

    private BankView(Player viewer, State state, Loans.Standing standing) {
        this.viewer = viewer;
        this.state = state;
        this.standing = standing;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.section(Main.getFinanceVillagerFredName() + " §8» §7Bank"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }


    /* open / load */

    public static void open(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        Loans.Standing standing = Loans.standing(player);
        Tasks.supplyAsync(() -> load(uuid, standing), state -> {
            if (!player.isOnline()) return;
            state.payouts().forEach(Bank::announce);
            BankView view = new BankView(player, state, standing);
            view.render();
            player.openInventory(view.inventory);
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.4f);
        });
    }

    // worker: due fixed deposits are paid out first, so the account is up to date
    private static State load(UUID uuid, Loans.Standing standing) {
        List<Bank.Payout> payouts = Bank.payoutDue(uuid);
        List<Bank.Entry> statement = Bank.statement(uuid, STATEMENT_LINES);
        return new State(PlayerRepository.getMoney(uuid), Bank.terms(uuid), statement, payouts, Bank.offer(uuid),
                Loans.current(uuid), Loans.rateNow(), Loans.limit(uuid, standing), Bonds.holding(uuid), transferNames(statement));
    }

    // worker: the names of transfer partners come from their player files (disk)
    private static Map<String, String> transferNames(List<Bank.Entry> statement) {
        Map<String, String> names = new HashMap<>();
        for (Bank.Entry entry : statement) {
            if (entry.note() == null || !(entry.kind().startsWith("TRANSFER_") || entry.kind().startsWith("SHOP_") || entry.kind().startsWith("PLEDGE"))) continue;
            try {
                String name = Bukkit.getOfflinePlayer(UUID.fromString(entry.note())).getName();
                if (name != null) names.put(entry.note(), name);
            } catch (IllegalArgumentException ignored) {
                // not a uuid
            }
        }
        return names;
    }

    // worker after a booking, then the view is drawn again
    private void reload() {
        UUID uuid = viewer.getUniqueId();
        State fresh;
        try {
            fresh = load(uuid, standing);
        } catch (RuntimeException e) {
            MainThread.run(() -> busy = false);
            return;
        }
        MainThread.run(() -> {
            busy = false;
            fresh.payouts().forEach(Bank::announce);
            state = fresh;
            render();
        });
    }


    /* render (main thread) */

    private void render() {
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);
        if (confirmLoan > 0) {
            renderLoanRules();
            return;
        }

        long cash = Cash.total(viewer);
        long legacy = legacyCash();
        inventory.setItem(SLOT_BALANCE, named(Material.GOLD_INGOT, "§fDein Konto: " + Main.getCurrencyName(state.balance()),
                List.of("§7Bargeld bei dir: §f" + cash + " Schilling")));
        inventory.setItem(SLOT_CLOSE, named(Material.BARRIER, "§cSchließen", List.of()));

        List<String> depositLore = new ArrayList<>();
        depositLore.add("§7Bei dir: §f" + cash + " Schilling §7in Scheinen");
        if (legacy > 0) depositLore.add("§7Alte Smaragd-Scheine: §f" + legacy + " Schilling");
        depositLore.add("§7Steuer beim Einzahlen: §f" + ShopView.percent(Taxes.cashDepositRate()));
        depositLore.add("");
        depositLore.add("§e» Linksklick: einzahlen");
        inventory.setItem(SLOT_DEPOSIT, named(Material.CHEST, "§aAlles Bargeld einzahlen", depositLore));

        for (int i = 0; i < Bank.WITHDRAW_AMOUNTS.length; i++) {
            int amount = Bank.WITHDRAW_AMOUNTS[i];
            int tax = Taxes.cashWithdrawTaxOn(amount);
            int paidOut = Math.max(0, amount - tax) / 10 * 10;
            inventory.setItem(WITHDRAW_START + i, named(Material.PAPER, "§e" + format(amount) + " Schilling abheben", List.of(
                    "§7Du bekommst §f" + format(paidOut) + " §7in Scheinen",
                    "§7Steuer: §f" + tax + " §7(" + ShopView.percent(Taxes.cashWithdrawRate()) + ")",
                    "",
                    "§e» Linksklick: abheben")));
        }

        Bank.Offer offer = state.offer();
        List<String> termLore = new ArrayList<>();
        termLore.add("§7Dein Geld liegt §f" + Bank.TERM_DAYS + " Tage §7in der Staatskasse,");
        termLore.add("§7sie zahlt es mit Zinsen zurück.");
        termLore.add("");
        if (offer.capacity() <= 0) {
            termLore.add(offer.playerLimited() ? "§cDu hast schon genug Festgeld laufen." : "§cGerade kein Festgeld möglich:");
            if (!offer.playerLimited()) termLore.add("§7Die Staatskasse hat zu wenig eingenommen.");
        } else {
            termLore.add("§7Zinsen gerade: §a" + rate(offer.rate()));
            termLore.add("§7Basil nimmt noch bis §f" + format(offer.capacity()) + " Schilling");
        }
        termLore.add("§7Der Zins richtet sich nach der Staatskasse und steht");
        termLore.add("§7beim Anlegen fest. Höchstens " + Bank.MAX_TERMS + " gleichzeitig.");
        termLore.add("§7Vorzeitig auflösen geht, dann ohne Zinsen.");
        inventory.setItem(SLOT_TERM_INFO, named(Material.CLOCK, "§6Festgeld", termLore));
        Material[] termIcons = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK};
        for (int i = 0; i < Bank.TERM_AMOUNTS.length; i++) {
            int amount = Bank.TERM_AMOUNTS[i];
            if (!offer.accepts(amount)) {
                inventory.setItem(TERM_START + i, named(Material.GRAY_DYE, "§7Festgeld: " + format(amount) + " Schilling",
                        List.of("§7Gerade nicht möglich.")));
                continue;
            }
            inventory.setItem(TERM_START + i, named(termIcons[i], "§6Festgeld: " + format(amount) + " Schilling", List.of(
                    "§7Nach " + Bank.TERM_DAYS + " Tagen: §a" + format(amount + Math.round(amount * offer.rate())) + " Schilling",
                    "§7(" + rate(offer.rate()) + " Zinsen)",
                    "",
                    "§e» Linksklick: anlegen")));
        }
        for (int i = 0; i < Bank.MAX_TERMS; i++) {
            if (i < state.terms().size()) {
                Bank.Term term = state.terms().get(i);
                inventory.setItem(RUNNING_START + i, named(Material.SUNFLOWER, "§6Laufend: " + format(term.amount()) + " Schilling", List.of(
                        "§7Zinsen: §a+" + term.interest() + " §7(" + rate(term.rate()) + ")",
                        "§7Fällig in: §f" + remaining(term.endsAt()),
                        "",
                        "§c» Rechtsklick: vorzeitig auflösen §7(ohne Zinsen)")));
            } else {
                inventory.setItem(RUNNING_START + i, named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7Kein Festgeld", List.of()));
            }
        }

        inventory.setItem(SLOT_STATEMENT, statementItem());
        inventory.setItem(SLOT_VAULT, named(Material.ENDER_CHEST, "§5Schließfach öffnen", List.of(
                "§7" + Vault.SIZE + " Plätze, sicher vor Tod, Dieben und Admins.",
                "§7Kostet §f" + Bank.VAULT_FEE + " Schilling §7pro Öffnen.",
                "",
                "§e» Linksklick: öffnen")));
        renderLoan();
        renderBonds();
    }

    private void renderBonds() {
        Bonds.Holding bonds = state.bonds();
        if (Bankruptcy.isBroke()) {
            List<String> lore = new ArrayList<>(List.of(
                    "§7Der Staat ist pleite. Leih ihm Geld,",
                    "§7er zahlt dir §a" + Math.round((1 + Bonds.RETURN) * 100) + " % §7zurück, sobald",
                    "§7er sich erholt hat und es sich leisten kann."));
            if (bonds.amount() > 0) lore.add("§7Deine Anleihen: §f" + format(bonds.amount()) + " §7→ zurück §a" + format(bonds.payout()));
            lore.add("§7Höchstens " + format(Bonds.MAX_TOTAL) + " pro Spieler.");
            lore.add("");
            lore.add("§e» Linksklick: " + format(Bonds.AMOUNTS[0]) + " kaufen");
            lore.add("§e» Rechtsklick: " + format(Bonds.AMOUNTS[1]) + " kaufen");
            inventory.setItem(SLOT_BONDS, named(Material.GOLD_BLOCK, "§6Staatsanleihe", lore));
        } else if (bonds.amount() > 0) {
            inventory.setItem(SLOT_BONDS, named(Material.GOLD_BLOCK, "§6Deine Staatsanleihen", List.of(
                    "§7Angelegt: §f" + format(bonds.amount()) + " §7→ zurück §a" + format(bonds.payout()),
                    "§7Der Staat zahlt, sobald er genug hat.")));
        }
    }

    private void buyBond(Player player, int amount) {
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            Bonds.Outcome outcome;
            try {
                outcome = Bonds.buy(uuid, amount);
            } catch (RuntimeException e) {
                outcome = Bonds.Outcome.UNAVAILABLE;
            }
            Bonds.Outcome result = outcome;
            MainThread.run(() -> {
                switch (result) {
                    case OK -> {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 1.4f);
                        player.sendMessage(Bank.PREFIX + "Danke! Der Staat schuldet dir jetzt §a"
                                + format(amount + Math.round(amount * Bonds.RETURN)) + " Schilling§f.");
                    }
                    case NOT_BROKE -> fail(player, "§fDer Staat braucht gerade keine Anleihen.");
                    case LIMIT -> fail(player, "§fMehr als " + format(Bonds.MAX_TOTAL) + " Schilling Anleihen nimmt der Staat nicht.");
                    case INSUFFICIENT_FUNDS -> fail(player, notEnough());
                    default -> fail(player, "§fBasil kann gerade nichts buchen, versuch es gleich nochmal.");
                }
            });
            reload();
        });
    }


    /* loans */

    private void renderLoan() {
        Loans.Loan loan = state.loan();
        Loans.Limit limit = state.loanLimit();
        int max = limit.max();
        List<String> info = new ArrayList<>(List.of(
                "§7Geld aus der Staatskasse, zurück in §f" + Loans.DAYS + " Tagen§7.",
                "§7Zinsen gerade: §f" + rate(state.loanRate()) + " §7pro Woche,",
                "§7je früher zurück, desto weniger."));
        info.add(max > 0 ? "§7Für dich gerade bis zu §f" + format(max) + " Schilling" : "§7Für dich gerade §ckein Kredit");
        info.addAll(limitLines(limit));
        info.add("§7Vor dem Kredit zeigt Basil dir alle Regeln.");
        inventory.setItem(SLOT_LOAN_INFO, named(Material.WRITABLE_BOOK, "§cKredit", info));
        Instant now = Instant.now();
        if (loan != null && loan.state() == Loans.State.OPEN) {
            inventory.setItem(SLOT_LOAN_STATUS, named(Material.PAPER, "§6Offener Kredit: " + format(loan.principal()) + " Schilling", List.of(
                    "§7Heute zurückzahlen: §f" + format(loan.debt(now)) + " Schilling",
                    "§7Am " + Loans.DAYS + ". Tag: §f" + format(loan.debt(loan.dueAt())) + " Schilling",
                    "§7Fällig in: §f" + remaining(loan.dueAt()),
                    "§7Kreditgeld gilt beim Händler und für Gebühren,",
                    "§7nicht für Bargeld, Kistenshops, Hondo, Notverkauf,",
                    "§7Team-Kasse, Kopfgeld, Festgeld oder Anleihen.")));
            inventory.setItem(SLOT_LOAN_ACTION, named(Material.LIME_CONCRETE, "§a§lJetzt zurückzahlen", List.of(
                    "§7Kostet heute §f" + format(loan.debt(now)) + " Schilling",
                    "",
                    "§e» Linksklick: zurückzahlen")));
            return;
        }
        if (loan != null && loan.state() == Loans.State.DEFAULTED) {
            boolean free = !isWantedOrJailed();
            long debt = Math.max(0, loan.locked() - loan.paid());
            List<String> status = new ArrayList<>(free
                    ? List.of("§7Deine Strafe ist vorbei. Für neue Kredite", "§7erwartet Basil eine Entschuldigung.")
                    : List.of("§cDu wirst gesucht oder sitzt im Gefängnis.", "§7Erst nach deiner Strafe kannst du", "§7dich bei Basil entschuldigen."));
            if (debt > 0) {
                status.add("");
                status.add("§cSchulden: " + format(debt) + " Schilling");
                status.add("§7Basil pfändet alles, was auf dein Konto kommt,");
                status.add("§7bis sie bezahlt sind.");
            }
            inventory.setItem(SLOT_LOAN_STATUS, named(Material.REDSTONE, "§4Kredit geplatzt", status));
            inventory.setItem(SLOT_LOAN_ACTION, free
                    ? named(Material.GOLD_BLOCK, "§6Entschuldigung zahlen", List.of(
                            "§7Kostet §f" + format(loan.apologyLeft()) + " Schilling §7(" + Loans.APOLOGY_FACTOR + "-fach, abzüglich eingezogen)",
                            "§7Danach sind wieder Kredite möglich.",
                            "",
                            "§e» Linksklick: zahlen"))
                    : named(Material.GRAY_DYE, "§7Entschuldigung erst nach der Strafe", List.of()));
            return;
        }
        for (int i = 0; i < Loans.AMOUNTS.length; i++) {
            int amount = Loans.AMOUNTS[i];
            if (amount > max) {
                inventory.setItem(LOAN_START + i, named(Material.GRAY_DYE, "§7Kredit: " + format(amount) + " Schilling", limitLines(limit)));
                continue;
            }
            inventory.setItem(LOAN_START + i, named(Material.EMERALD, "§cKredit: " + format(amount) + " Schilling", List.of(
                    "§7Zurück in " + Loans.DAYS + " Tagen, höchstens §f" + format(amount + (int) Math.ceil(amount * state.loanRate())),
                    "",
                    "§e» Linksklick: Regeln ansehen")));
        }
    }

    // why the loan is not bigger, and how it grows
    private List<String> limitLines(Loans.Limit limit) {
        return switch (limit.reason()) {
            case NEW -> List.of("§7Kredite erst ab §f" + Loans.MIN_HOURS + " Stunden Spielzeit", "§7und §f" + Loans.MIN_DAYS + " Tagen §7in dieser Saison.");
            case CLOSED -> List.of("§7Basil vergibt gerade", "§7keine neuen Kredite.");
            case TREASURY -> List.of("§7Die Staatskasse gibt gerade", "§7nicht mehr her.");
            case INCOME -> List.of("§7Höchstens das " + Loans.INCOME_FACTOR + "-fache deiner Einnahmen", "§7der letzten " + Loans.INCOME_DAYS
                    + " Tage §7(Händler, Bauamt, Zinsen, Kopfgeld): §f" + format(limit.income()) + "§7.");
            case TIER, FULL -> nextTierLines(limit);
        };
    }

    // what the next step needs, ticked where the player is there already
    private List<String> nextTierLines(Loans.Limit limit) {
        Loans.Tier next = Loans.nextTier(limit.tierMax());
        if (next == null) return List.of("§7Du hast die höchste Stufe erreicht.");
        Loans.Standing now = standing;
        List<String> lines = new ArrayList<>();
        lines.add("§7Nächste Stufe §f" + format(next.amount()) + " Schilling§7:");
        lines.add(tick(limit.repaid() >= next.repaid()) + next.repaid() + " pünktlich zurückgezahlte Kredite §7(du: " + limit.repaid() + ")");
        if (next.largest() > 0) {
            lines.add(tick(limit.largest() >= next.largest()) + "einer davon ab " + format(next.largest()) + " §7(du: " + format(limit.largest()) + ")");
        }
        lines.add(tick(now.hours() >= next.hours()) + next.hours() + " Stunden Spielzeit §7(du: " + now.hours() + ")");
        lines.add(tick(now.days() >= next.days()) + next.days() + " Tage in dieser Saison §7(du: " + now.days() + ")");
        return lines;
    }

    private static String tick(boolean done) {
        return done ? "§a✔ §f" : "§c✘ §f";
    }

    private void renderLoanRules() {
        int amount = confirmLoan;
        double rate = state.loanRate();
        int tomorrow = amount + (int) Math.ceil(amount * rate / Loans.DAYS);
        int full = amount + (int) Math.ceil(amount * rate);
        inventory.setItem(4, named(Material.EMERALD, "§cKredit über " + format(amount) + " Schilling", List.of()));
        inventory.setItem(SLOT_RULES, named(Material.WRITABLE_BOOK, "§fKredit-Regeln und Folgen", List.of(
                "§a1. §fDu bekommst §a" + format(amount) + " Schilling §faus der Staatskasse.",
                "§a2. §fZurück in §f" + Loans.DAYS + " Tagen§f, je früher desto billiger:",
                "   §7nach 1 Tag §f" + format(tomorrow) + "§7, am " + Loans.DAYS + ". Tag §f" + format(full) + " Schilling",
                "§a3. §fDas Geld gilt beim Händler und für Gebühren,",
                "   §7nicht für Bargeld, Kistenshops, Hondo, Notverkauf,",
                "   §7Team-Kasse, Kopfgeld, Festgeld oder Anleihen.",
                "§a4. §f" + Loans.REMIND_DAYS + " Tage vorher erinnert dich Basil,",
                "   §fam " + Loans.DAYS + ". Tag holt er es sich vom Konto.",
                "§c5. §fReicht dein Geld nicht, nimmt er was da ist,",
                "   §cund du wirst gesucht§f, bis dich jemand erwischt.",
                "   §fDann §c72 Stunden Gefängnis§f. Der Rest bleibt Schuld:",
                "   §cBasil pfändet alles, was auf dein Konto kommt§f.",
                "§c6. §fNeue Kredite erst nach einer Entschuldigung",
                "   §fvon §c" + format(Loans.apology(amount)) + " Schilling §7(" + Loans.APOLOGY_FACTOR + "-fach)§f.")));
        inventory.setItem(SLOT_CONFIRM, named(Material.LIME_CONCRETE, "§a§lJa, Kredit aufnehmen", List.of(
                "§7Zinsen: §f" + rate(rate) + " §7pro Woche")));
        inventory.setItem(SLOT_CANCEL, named(Material.RED_CONCRETE, "§cAbbrechen", List.of()));
    }

    // fixed deposits and bonds: an open loan locks the borrowed money there, the player should know why
    private String notEnough() {
        Loans.Loan loan = state.loan();
        if (loan != null && loan.state() == Loans.State.OPEN) return "§fSo viel geht nicht: geliehenes Geld kommt nicht ins Festgeld oder in Anleihen.";
        if (loan != null && loan.state() == Loans.State.DEFAULTED && loan.locked() > loan.paid()) {
            return "§fSo viel geht nicht: dein Geld gehört Basil, bis deine Kreditschulden bezahlt sind.";
        }
        return "§fSo viel hast du nicht auf dem Konto.";
    }

    private boolean isWantedOrJailed() {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(viewer);
        return pco.isWanted() || pco.isJail();
    }

    private void takeLoan(Player player, int amount) {
        UUID uuid = player.getUniqueId();
        double rate = state.loanRate();
        Loans.Standing now = Loans.standing(player);
        standing = now;
        busy = true;
        Tasks.async(() -> {
            Loans.Outcome outcome;
            try {
                outcome = Loans.take(uuid, amount, rate, now);
            } catch (RuntimeException e) {
                outcome = Loans.Outcome.UNAVAILABLE;
            }
            Loans.Outcome result = outcome;
            MainThread.run(() -> {
                confirmLoan = 0;
                switch (result) {
                    case OK -> {
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
                        player.sendMessage(Bank.PREFIX + "Hier sind §a" + format(amount) + " Schilling§f. In " + Loans.DAYS + " Tagen will ich sie zurück.");
                    }
                    case HAS_LOAN -> fail(player, "§fDu hast schon einen Kredit.");
                    case NOT_ALLOWED -> fail(player, "§fErst die Entschuldigung, dann ein neuer Kredit.");
                    case TOO_HIGH -> fail(player, "§fSo viel leiht Basil dir noch nicht.");
                    case TOO_NEW -> fail(player, "§fBasil kennt dich noch nicht lange genug für einen Kredit.");
                    case CLOSED -> fail(player, "§fBasil vergibt gerade keine neuen Kredite.");
                    case TREASURY_EMPTY -> fail(player, "§fDie Staatskasse gibt gerade so viel nicht her.");
                    case WANTED -> fail(player, "§fGesuchten und Häftlingen leiht Basil nichts.");
                    case RATE_CHANGED -> fail(player, "§fDer Zins hat sich gerade geändert, schau ihn dir nochmal an.");
                    default -> fail(player, "§fBasil kann gerade nichts buchen, versuch es gleich nochmal.");
                }
            });
            reload();
        });
    }

    private void repayLoan(Player player) {
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            Loans.Outcome outcome;
            try {
                outcome = Loans.repay(uuid);
            } catch (RuntimeException e) {
                outcome = Loans.Outcome.UNAVAILABLE;
            }
            Loans.Outcome result = outcome;
            MainThread.run(() -> {
                switch (result) {
                    case OK -> {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                        player.sendMessage(Bank.PREFIX + "Kredit zurückgezahlt. Mit dir mache ich gern Geschäfte.");
                    }
                    case INSUFFICIENT_FUNDS -> fail(player, "§fDafür reicht dein Konto gerade nicht.");
                    default -> fail(player, "§fDa ist kein offener Kredit.");
                }
            });
            reload();
        });
    }

    private void apologize(Player player) {
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            Loans.Outcome outcome;
            try {
                outcome = Loans.apologize(uuid);
            } catch (RuntimeException e) {
                outcome = Loans.Outcome.UNAVAILABLE;
            }
            Loans.Outcome result = outcome;
            MainThread.run(() -> {
                switch (result) {
                    case OK -> {
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 1f, 1f);
                        player.sendMessage(Bank.PREFIX + "Entschuldigung angenommen. Neuer Anfang, aber ich behalte dich im Auge.");
                    }
                    case INSUFFICIENT_FUNDS -> fail(player, "§fDafür reicht dein Konto gerade nicht.");
                    case WANTED -> fail(player, "§fErst die Strafe, dann die Entschuldigung.");
                    default -> fail(player, "§fDa gibt es nichts zu entschuldigen.");
                }
            });
            reload();
        });
    }

    private long legacyCash() {
        long total = 0;
        for (ItemStack stack : viewer.getInventory().getStorageContents()) {
            if (Cash.isLegacy(stack)) total += 10L * stack.getAmount();
        }
        return total;
    }

    private ItemStack statementItem() {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.customName(Text.of("§fKontoauszug"));
        List<Component> lore = new ArrayList<>();
        if (state.statement().isEmpty()) lore.add(line("§7Noch keine Buchungen."));
        for (Bank.Entry entry : state.statement()) {
            String amount = entry.amount() >= 0 ? "§a+" + format(entry.amount()) : "§c-" + format(-entry.amount());
            lore.add(line("§7" + DATE.format(entry.at()) + " " + amount + " §7").append(label(entry, state.names())));
        }
        meta.lore(lore);
        book.setItemMeta(meta);
        return book;
    }

    private static Component label(Bank.Entry entry, Map<String, String> names) {
        String kind = entry.kind();
        if (kind.startsWith("MARKET_")) {
            if (entry.note() != null && entry.note().startsWith("SERVICE:")) {
                ServiceOffer offer = Services.get(entry.note().substring("SERVICE:".length()));
                String name = offer == null ? "?" : Text.strip(Text.legacy(Services.name(offer)));
                return Component.text("Dienst: " + name, NamedTextColor.GRAY);
            }
            Material material = entry.note() == null ? null : Material.matchMaterial(entry.note());
            Component what = material == null ? Component.text("?") : Component.translatable(material.translationKey());
            return Component.text(kind.equals("MARKET_BUY") ? "Kauf: " : "Verkauf: ", NamedTextColor.GRAY).append(what.color(NamedTextColor.GRAY));
        }
        String text = switch (kind) {
            case BankLog.DEPOSIT -> "Bargeld eingezahlt";
            case BankLog.WITHDRAW -> "Bargeld abgehoben";
            case BankLog.TRANSFER_OUT -> "Überweisung an " + names.getOrDefault(entry.note(), "?");
            case BankLog.TRANSFER_IN -> "Überweisung von " + names.getOrDefault(entry.note(), "?");
            case BankLog.TERM_START -> "Festgeld angelegt";
            case BankLog.TERM_PAYOUT -> "Festgeld ausgezahlt" + (entry.note() == null ? "" : " (" + entry.note() + " Zinsen)");
            case BankLog.TERM_CANCEL -> "Festgeld aufgelöst";
            case BankLog.VAULT_FEE -> "Schließfach";
            case BankLog.LOAN_TAKE -> "Kredit aufgenommen";
            case BankLog.LOAN_REPAY -> "Kredit zurückgezahlt" + (entry.note() == null ? "" : " (" + entry.note() + " Zinsen)");
            case BankLog.LOAN_DEFAULT -> "Kredit eingezogen";
            case BankLog.LOAN_APOLOGY -> "Entschuldigung an Basil";
            case BankLog.BOND_BUY -> "Staatsanleihe gekauft";
            case BankLog.BOND_REPAY -> "Staatsanleihe zurückgezahlt";
            case BankLog.TEAM_DEPOSIT -> "In die Team-Kasse";
            case BankLog.TEAM_WITHDRAW -> "Aus der Team-Kasse";
            case BankLog.BOUNTY -> "Kopfgeld" + (entry.note() == null ? "" : " für " + entry.note());
            case BankLog.PLEDGE -> "Kopfgeld ausgesetzt auf " + names.getOrDefault(entry.note(), "?");
            case BankLog.PLEDGE_REFUND -> "Kopfgeld zurück (" + names.getOrDefault(entry.note(), "?") + ")";
            case "TAX_DEATH_TAX" -> "Todessteuer";
            case "TAX_NETHER_TAX" -> "Nether-Steuer";
            case "TAX_FEES" -> "Gebühr";
            case ChestShopRepository.BUY -> "Kistenshop von " + names.getOrDefault(entry.note(), "?");
            case ChestShopRepository.SALE -> "Kistenshop-Verkauf an " + names.getOrDefault(entry.note(), "?");
            case ChestShopRepository.REFUND -> "Kistenshop erstattet";
            default -> kind;
        };
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static Component line(String legacy) {
        return Text.section(legacy).decoration(TextDecoration.ITALIC, false);
    }

    static String remaining(Instant end) {
        Duration left = Duration.between(Instant.now(), end);
        if (left.isNegative()) return "gleich";
        long days = left.toDays();
        long hours = left.toHoursPart();
        return days > 0 ? days + " T " + hours + " Std" : hours + " Std " + left.toMinutesPart() + " Min";
    }

    private static String rate(double rate) {
        double percent = Math.round(rate * 1000) / 10.0;
        return (percent == Math.floor(percent) ? String.valueOf((long) percent) : String.valueOf(percent).replace('.', ',')) + " %";
    }

    private static String format(long amount) {
        return String.format("%,d", amount).replace(',', '.');
    }


    /* clicks (main thread) */

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || busy) return;
        if (System.currentTimeMillis() < ignoreClicksUntil) return;
        ClickType click = event.getClick();
        // only plain clicks: no shift, double, number key, drop or swap clicks
        if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
        int slot = event.getRawSlot();

        if (confirmLoan > 0) {
            if (slot == SLOT_CONFIRM && click == ClickType.LEFT) {
                takeLoan(player, confirmLoan);
            } else if (slot == SLOT_CANCEL) {
                confirmLoan = 0;
                render();
            }
            return;
        }
        if (slot == SLOT_CLOSE) {
            Tasks.sync(player::closeInventory);
        } else if (slot == SLOT_DEPOSIT && click == ClickType.LEFT) {
            deposit(player);
        } else if (slot >= WITHDRAW_START && slot < WITHDRAW_START + Bank.WITHDRAW_AMOUNTS.length && click == ClickType.LEFT) {
            withdraw(player, Bank.WITHDRAW_AMOUNTS[slot - WITHDRAW_START]);
        } else if (slot >= TERM_START && slot < TERM_START + Bank.TERM_AMOUNTS.length && click == ClickType.LEFT) {
            startTerm(player, Bank.TERM_AMOUNTS[slot - TERM_START]);
        } else if (slot >= RUNNING_START && slot < RUNNING_START + Bank.MAX_TERMS && click == ClickType.RIGHT) {
            int index = slot - RUNNING_START;
            if (index < state.terms().size()) cancelTerm(player, state.terms().get(index));
        } else if (slot == SLOT_VAULT && click == ClickType.LEFT) {
            openVault(player);
        } else if (slot == SLOT_BONDS && Bankruptcy.isBroke()) {
            buyBond(player, click == ClickType.LEFT ? Bonds.AMOUNTS[0] : Bonds.AMOUNTS[1]);
        } else if (click == ClickType.LEFT && slot >= LOAN_START && slot < LOAN_START + Loans.AMOUNTS.length) {
            clickLoan(player, slot);
        }
    }

    private void clickLoan(Player player, int slot) {
        Loans.Loan loan = state.loan();
        if (loan != null) {
            if (slot != SLOT_LOAN_ACTION) return;
            if (loan.state() == Loans.State.OPEN) {
                repayLoan(player);
            } else if (!isWantedOrJailed()) {
                apologize(player);
            }
            return;
        }
        int amount = Loans.AMOUNTS[slot - LOAN_START];
        if (amount > state.loanLimit().max()) return;
        // the rules first, the loan only after the confirmation
        confirmLoan = amount;
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        render();
    }

    private void deposit(Player player) {
        if (AdminSettings.stopped(AdminSettings.STOP_CASH)) {
            fail(player, "§fBasil nimmt gerade kein Bargeld an.");
            return;
        }
        // taken right away on the main thread, so the same notes cannot be paid in twice
        long amount = Cash.takeAll(player, true);
        if (amount <= 0) {
            fail(player, Cash.hasOldSeason(player)
                    ? "§fDeine Scheine sind aus der letzten Saison und nichts mehr wert."
                    : "§fDu hast kein Bargeld dabei.");
            return;
        }
        // the notes leave the player file soon, not at the next autosave (a hard crash would hand them back)
        PlayerSaves.soon(player);
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            Bank.Deposit result;
            try {
                result = Bank.deposit(uuid, amount);
            } catch (RuntimeException e) {
                result = null;
            }
            Bank.Deposit booked = result;
            if (booked == null) {
                Main.getInstance().getSLF4JLogger().warn("Deposit of {} ({}) failed, the cash goes back", uuid, amount);
                MainThread.run(() -> {
                    busy = false;
                    Player online = Bukkit.getPlayer(uuid);
                    if (online == null) {
                        Main.getInstance().getSLF4JLogger().error("Cash of {} ({}) could not be booked nor given back", uuid, amount);
                        return;
                    }
                    Cash.give(online, amount);
                    fail(online, "§fBasil kann gerade nichts buchen, du bekommst dein Bargeld zurück.");
                });
                return;
            }
            Logger.console("deposit from " + uuid + " (" + amount + ")");
            MainThread.run(() -> {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                player.sendMessage(Bank.PREFIX + "§a" + format(booked.credited()) + " Schilling §feingezahlt"
                        + (booked.tax() > 0 ? " §7(" + format(amount) + " in Scheinen, Steuer " + format(booked.tax()) + ")" : "") + "§f.");
            });
            reload();
        });
    }

    private void withdraw(Player player, int amount) {
        if (AdminSettings.stopped(AdminSettings.STOP_CASH)) {
            fail(player, "§fBasil zahlt gerade kein Bargeld aus.");
            return;
        }
        if (amount > state.balance()) {
            fail(player, "§fSo viel hast du nicht auf dem Konto.");
            return;
        }
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            Taxes.CashWithdrawal withdrawal;
            try {
                withdrawal = Taxes.withdrawCash(uuid, amount);
            } catch (RuntimeException e) {
                MainThread.run(() -> {
                    busy = false;
                    fail(player, "§fBasil kann gerade nichts buchen, versuch es gleich nochmal.");
                });
                return;
            }
            if (!withdrawal.success()) {
                String bound = Loans.lockedMessage(uuid);
                MainThread.run(() -> {
                    busy = false;
                    fail(player, bound != null ? bound : "§fSo viel hast du nicht auf dem Konto.");
                });
                return;
            }
            Logger.console("withdraw from " + uuid + " (" + withdrawal.cash() + " cash, " + withdrawal.tax() + " tax)");
            boolean scheduled = MainThread.run(() -> {
                Bank.handOut(uuid, withdrawal.cash(), withdrawal.tax());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                player.sendMessage(Bank.PREFIX + "Hier, §a" + format(withdrawal.cash()) + " Schilling §fin Scheinen §7(Steuer: " + withdrawal.tax() + ")");
            });
            // server stop before the hand-out: everything back, the tax leaves the treasury again
            if (!scheduled) Taxes.refundWithdrawal(uuid, withdrawal.cash(), withdrawal.tax());
            reload();
        });
    }

    private void startTerm(Player player, int amount) {
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            Bank.Outcome outcome;
            try {
                outcome = Bank.startTerm(uuid, amount);
            } catch (RuntimeException e) {
                outcome = Bank.Outcome.UNAVAILABLE;
            }
            Bank.Outcome result = outcome;
            MainThread.run(() -> {
                switch (result) {
                    case OK -> {
                        player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 0.8f, 1.4f);
                        player.sendMessage(Bank.PREFIX + "§a" + format(amount) + " Schilling §fliegen jetzt " + Bank.TERM_DAYS + " Tage fest.");
                    }
                    case INSUFFICIENT_FUNDS -> fail(player, notEnough());
                    case LIMIT -> fail(player, "§fMehr Festgeld geht für dich gerade nicht §7(höchstens " + Bank.MAX_TERMS
                            + " gleichzeitig, und alle sollen etwas abbekommen)§f.");
                    case CLOSED -> fail(player, "§fSo viel Festgeld nimmt Basil gerade nicht an, die Staatskasse gibt nicht mehr her.");
                    default -> fail(player, "§fBasil kann gerade nichts buchen, versuch es gleich nochmal.");
                }
            });
            reload();
        });
    }

    private void cancelTerm(Player player, Bank.Term term) {
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            boolean cancelled;
            try {
                cancelled = Bank.cancelTerm(uuid, term.id());
            } catch (RuntimeException e) {
                cancelled = false;
            }
            boolean done = cancelled;
            MainThread.run(() -> {
                if (done) {
                    player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 0.8f, 1.2f);
                    player.sendMessage(Bank.PREFIX + "Festgeld aufgelöst: §a" + format(term.amount()) + " Schilling §fsind wieder auf dem Konto.");
                } else {
                    fail(player, "§fDas Festgeld gibt es so nicht mehr.");
                }
            });
            reload();
        });
    }

    private void openVault(Player player) {
        // no stashing the gear mid-fight (the fee is not taken then)
        if (CombatLock.denies(player)) return;
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            boolean paid;
            try {
                paid = Bank.chargeVaultFee(uuid);
            } catch (RuntimeException e) {
                paid = false;
            }
            boolean ok = paid;
            MainThread.run(() -> {
                busy = false;
                if (!ok) {
                    fail(player, "§fDas Schließfach kostet " + Bank.VAULT_FEE + " Schilling pro Öffnen.");
                    return;
                }
                // left while the fee was booked: it goes back, the vault was never opened
                if (!player.isOnline()) {
                    Tasks.asyncOrNow(() -> Taxes.refund(uuid, 0, Bank.VAULT_FEE, Treasury.Source.VAULT_FEE));
                    return;
                }
                // the counter closes first, so no second click can pay a second time while the vault loads
                player.closeInventory();
                Vault.open(player);
            });
        });
    }

}
