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
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.database.BankLog;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
                         Loans.@Nullable Loan loan, double loanRate) {}

    private BankView(Player viewer, State state) {
        this.viewer = viewer;
        this.state = state;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.section(Main.getFinanceVillagerFredName() + " §8» §7Bank"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }


    /* open / load */

    public static void open(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.supplyAsync(() -> load(uuid), state -> {
            if (!player.isOnline()) return;
            state.payouts().forEach(Bank::announce);
            BankView view = new BankView(player, state);
            view.render();
            player.openInventory(view.inventory);
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.4f);
        });
    }

    // worker: due fixed deposits are paid out first, so the account is up to date
    private static State load(UUID uuid) {
        List<Bank.Payout> payouts = Bank.payoutDue(uuid);
        return new State(PlayerRepository.getMoney(uuid), Bank.terms(uuid), Bank.statement(uuid, STATEMENT_LINES), payouts, Bank.offer(uuid),
                Loans.current(uuid), Loans.rateNow());
    }

    // worker after a booking, then the view is drawn again
    private void reload() {
        UUID uuid = viewer.getUniqueId();
        State fresh;
        try {
            fresh = load(uuid);
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
        depositLore.add("");
        depositLore.add("§e» Linksklick: einzahlen");
        inventory.setItem(SLOT_DEPOSIT, named(Material.CHEST, "§aAlles Bargeld einzahlen", depositLore));

        for (int i = 0; i < Bank.WITHDRAW_AMOUNTS.length; i++) {
            int amount = Bank.WITHDRAW_AMOUNTS[i];
            int tax = Taxes.taxOn(amount, TaxClass.STANDARD);
            int paidOut = Math.max(0, amount - tax) / 10 * 10;
            inventory.setItem(WITHDRAW_START + i, named(Material.PAPER, "§e" + format(amount) + " Schilling abheben", List.of(
                    "§7Du bekommst §f" + format(paidOut) + " §7in Scheinen",
                    "§7Steuer: §f" + tax + " §8(" + ShopView.percent(Taxes.rate(TaxClass.STANDARD)) + ")",
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
        termLore.add("§8Der Zins richtet sich nach der Staatskasse und steht");
        termLore.add("§8beim Anlegen fest. Höchstens " + Bank.MAX_TERMS + " gleichzeitig.");
        termLore.add("§8Vorzeitig auflösen geht, dann ohne Zinsen.");
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
                    "§8(" + rate(offer.rate()) + " Zinsen)",
                    "",
                    "§e» Linksklick: anlegen")));
        }
        for (int i = 0; i < Bank.MAX_TERMS; i++) {
            if (i < state.terms().size()) {
                Bank.Term term = state.terms().get(i);
                inventory.setItem(RUNNING_START + i, named(Material.SUNFLOWER, "§6Laufend: " + format(term.amount()) + " Schilling", List.of(
                        "§7Zinsen: §a+" + term.interest() + " §8(" + rate(term.rate()) + ")",
                        "§7Fällig in: §f" + remaining(term.endsAt()),
                        "",
                        "§c» Rechtsklick: vorzeitig auflösen §8(ohne Zinsen)")));
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
    }


    /* loans */

    private void renderLoan() {
        Loans.Loan loan = state.loan();
        int max = Loans.maxFor(state.balance());
        inventory.setItem(SLOT_LOAN_INFO, named(Material.WRITABLE_BOOK, "§cKredit", List.of(
                "§7Geld sofort, zurück in §f" + Loans.DAYS + " Tagen§7.",
                "§7Zinsen gerade: §f" + rate(state.loanRate()) + " §7pro Woche,",
                "§7je früher zurück, desto weniger.",
                "§7Für dich bis zu §f" + format(max) + " Schilling §8(nach Kontostand)",
                "§8Vor dem Kredit zeigt Basil dir alle Regeln.")));
        Instant now = Instant.now();
        if (loan != null && loan.state() == Loans.State.OPEN) {
            inventory.setItem(SLOT_LOAN_STATUS, named(Material.PAPER, "§6Offener Kredit: " + format(loan.principal()) + " Schilling", List.of(
                    "§7Heute zurückzahlen: §f" + format(loan.debt(now)) + " Schilling",
                    "§7Am " + Loans.DAYS + ". Tag: §f" + format(loan.debt(loan.dueAt())) + " Schilling",
                    "§7Fällig in: §f" + remaining(loan.dueAt()),
                    "§8Gesperrt bis dahin: " + format(loan.locked()) + " Schilling",
                    "§8(nicht überweisen, abheben oder festlegen)")));
            inventory.setItem(SLOT_LOAN_ACTION, named(Material.LIME_CONCRETE, "§a§lJetzt zurückzahlen", List.of(
                    "§7Kostet heute §f" + format(loan.debt(now)) + " Schilling",
                    "",
                    "§e» Linksklick: zurückzahlen")));
            return;
        }
        if (loan != null && loan.state() == Loans.State.DEFAULTED) {
            boolean free = !isWantedOrJailed();
            inventory.setItem(SLOT_LOAN_STATUS, named(Material.REDSTONE, "§4Kredit geplatzt", free
                    ? List.of("§7Deine Strafe ist vorbei. Für neue Kredite", "§7erwartet Basil eine Entschuldigung.")
                    : List.of("§cDu wirst gesucht oder sitzt im Gefängnis.", "§7Erst nach deiner Strafe kannst du", "§7dich bei Basil entschuldigen.")));
            inventory.setItem(SLOT_LOAN_ACTION, free
                    ? named(Material.GOLD_BLOCK, "§6Entschuldigung zahlen", List.of(
                            "§7Kostet §f" + format(loan.apologyLeft()) + " Schilling §8(" + Loans.APOLOGY_FACTOR + "-fach, abzüglich eingezogen)",
                            "§7Danach sind wieder Kredite möglich.",
                            "",
                            "§e» Linksklick: zahlen"))
                    : named(Material.GRAY_DYE, "§7Entschuldigung erst nach der Strafe", List.of()));
            return;
        }
        for (int i = 0; i < Loans.AMOUNTS.length; i++) {
            int amount = Loans.AMOUNTS[i];
            if (amount > max) {
                inventory.setItem(LOAN_START + i, named(Material.GRAY_DYE, "§7Kredit: " + format(amount) + " Schilling",
                        List.of("§7Dafür ist dein Kontostand zu niedrig.")));
                continue;
            }
            inventory.setItem(LOAN_START + i, named(Material.EMERALD, "§cKredit: " + format(amount) + " Schilling", List.of(
                    "§7Zurück in " + Loans.DAYS + " Tagen, höchstens §f" + format(amount + (int) Math.ceil(amount * state.loanRate())),
                    "",
                    "§e» Linksklick: Regeln ansehen")));
        }
    }

    private void renderLoanRules() {
        int amount = confirmLoan;
        double rate = state.loanRate();
        int tomorrow = amount + (int) Math.ceil(amount * rate / Loans.DAYS);
        int full = amount + (int) Math.ceil(amount * rate);
        inventory.setItem(4, named(Material.EMERALD, "§cKredit über " + format(amount) + " Schilling", List.of()));
        inventory.setItem(SLOT_RULES, named(Material.WRITABLE_BOOK, "§fKredit-Regeln und Folgen", List.of(
                "§a1. §fDu bekommst §a" + format(amount) + " Schilling §fsofort.",
                "§a2. §fZurück in §f" + Loans.DAYS + " Tagen§f, je früher desto billiger:",
                "   §7nach 1 Tag §f" + format(tomorrow) + "§7, am " + Loans.DAYS + ". Tag §f" + format(full) + " Schilling",
                "§a3. §fBis dahin ist das geliehene Geld gesperrt:",
                "   §7ausgeben ja, überweisen, abheben, festlegen nein.",
                "§a4. §fAm " + Loans.DAYS + ". Tag holt Basil es sich vom Konto.",
                "§c5. §fReicht dein Geld nicht, nimmt er was da ist,",
                "   §cund du wirst gesucht§f, bis dich jemand erwischt.",
                "   §fDann §c72 Stunden Gefängnis§f.",
                "§c6. §fNeue Kredite erst nach einer Entschuldigung",
                "   §fvon §c" + format(Loans.apology(amount)) + " Schilling §8(" + Loans.APOLOGY_FACTOR + "-fach)§f.")));
        inventory.setItem(SLOT_CONFIRM, named(Material.LIME_CONCRETE, "§a§lJa, Kredit aufnehmen", List.of(
                "§7Zinsen: §f" + rate(rate) + " §7pro Woche")));
        inventory.setItem(SLOT_CANCEL, named(Material.RED_CONCRETE, "§cAbbrechen", List.of()));
    }

    // an open loan locks the borrowed money, the player should know why
    private String notEnough() {
        Loans.Loan loan = state.loan();
        return loan != null && loan.state() == Loans.State.OPEN
                ? "§fSo viel geht nicht: geliehenes Geld bleibt bis zur Rückzahlung gesperrt."
                : "§fSo viel hast du nicht auf dem Konto.";
    }

    private boolean isWantedOrJailed() {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(viewer);
        return pco.isWanted() || pco.isJail();
    }

    private void takeLoan(Player player, int amount) {
        UUID uuid = player.getUniqueId();
        double rate = state.loanRate();
        busy = true;
        Tasks.async(() -> {
            Loans.Outcome outcome;
            try {
                outcome = Loans.take(uuid, amount, rate);
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
                    case TOO_HIGH -> fail(player, "§fSo viel leiht Basil dir bei deinem Kontostand nicht.");
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
            lore.add(line("§8" + DATE.format(entry.at()) + " " + amount + " §7").append(label(entry)));
        }
        meta.lore(lore);
        book.setItemMeta(meta);
        return book;
    }

    private static Component label(Bank.Entry entry) {
        String kind = entry.kind();
        if (kind.startsWith("MARKET_")) {
            Material material = entry.note() == null ? null : Material.matchMaterial(entry.note());
            Component what = material == null ? Component.text("?") : Component.translatable(material.translationKey());
            return Component.text(kind.equals("MARKET_BUY") ? "Kauf: " : "Verkauf: ", NamedTextColor.GRAY).append(what.color(NamedTextColor.GRAY));
        }
        String text = switch (kind) {
            case BankLog.DEPOSIT -> "Bargeld eingezahlt";
            case BankLog.WITHDRAW -> "Bargeld abgehoben";
            case BankLog.TRANSFER_OUT -> "Überweisung an " + name(entry.note());
            case BankLog.TRANSFER_IN -> "Überweisung von " + name(entry.note());
            case BankLog.TERM_START -> "Festgeld angelegt";
            case BankLog.TERM_PAYOUT -> "Festgeld ausgezahlt" + (entry.note() == null ? "" : " (" + entry.note() + " Zinsen)");
            case BankLog.TERM_CANCEL -> "Festgeld aufgelöst";
            case BankLog.VAULT_FEE -> "Schließfach";
            case BankLog.LOAN_TAKE -> "Kredit aufgenommen";
            case BankLog.LOAN_REPAY -> "Kredit zurückgezahlt" + (entry.note() == null ? "" : " (" + entry.note() + " Zinsen)");
            case BankLog.LOAN_DEFAULT -> "Kredit eingezogen";
            case BankLog.LOAN_APOLOGY -> "Entschuldigung an Basil";
            case "TAX_DEATH_TAX" -> "Todessteuer";
            case "TAX_NETHER_TAX" -> "Nether-Steuer";
            default -> kind;
        };
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static String name(@Nullable String uuid) {
        if (uuid == null) return "?";
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName();
            return name == null ? "?" : name;
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }

    private static Component line(String legacy) {
        return Text.section(legacy).decoration(TextDecoration.ITALIC, false);
    }

    private static String remaining(Instant end) {
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
        if (amount > Loans.maxFor(state.balance())) return;
        // the rules first, the loan only after the confirmation
        confirmLoan = amount;
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        render();
    }

    private void deposit(Player player) {
        // taken right away on the main thread, so the same notes cannot be paid in twice
        long amount = Cash.takeAll(player, true);
        if (amount <= 0) {
            fail(player, "§fDu hast kein Bargeld dabei.");
            return;
        }
        UUID uuid = player.getUniqueId();
        busy = true;
        Tasks.async(() -> {
            boolean booked;
            try {
                booked = Bank.deposit(uuid, amount);
            } catch (RuntimeException e) {
                booked = false;
            }
            if (!booked) {
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
                player.sendMessage(Bank.PREFIX + "§a" + format(amount) + " Schilling §feingezahlt.");
            });
            reload();
        });
    }

    private void withdraw(Player player, int amount) {
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
                MainThread.run(() -> {
                    busy = false;
                    fail(player, notEnough());
                });
                return;
            }
            Logger.console("withdraw from " + uuid + " (" + withdrawal.cash() + " cash, " + withdrawal.tax() + " tax)");
            MainThread.deliverOrRefund(uuid, withdrawal.cash() + withdrawal.tax(), () -> {
                Bank.handOut(uuid, withdrawal.cash());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                player.sendMessage(Bank.PREFIX + "Hier, §a" + format(withdrawal.cash()) + " Schilling §fin Scheinen §8(Steuer: " + withdrawal.tax() + ")");
            });
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
                    case LIMIT -> fail(player, "§fMehr Festgeld geht für dich gerade nicht §8(höchstens " + Bank.MAX_TERMS
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
                if (!player.isOnline()) return;
                // the counter closes first, so no second click can pay a second time while the vault loads
                player.closeInventory();
                Vault.open(player);
            });
        });
    }

}
