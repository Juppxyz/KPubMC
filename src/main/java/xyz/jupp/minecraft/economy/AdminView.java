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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.database.SeasonReset;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.List;

import static xyz.jupp.minecraft.economy.ShopView.named;
import static xyz.jupp.minecraft.economy.ShopView.pane;
import static xyz.jupp.minecraft.economy.ShopView.percent;

/**
 * /wirtschaft, admins only: the economy at a glance and the switches of {@link AdminSettings}. Left click lowers or
 * switches back, right click raises or switches on, shift + left click puts a setting back to its default.
 * Players never see these decisions as such (no dates, no "the admin closed it").
 */
public final class AdminView implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_CLOSE = 8;
    private static final int SLOT_NETHERITE = 10;
    private static final int SLOT_NETHERITE_LIMIT = 11;
    private static final int SLOT_DIAMOND = 12;
    private static final int SLOT_WITHDRAW = 13;
    private static final int SLOT_DEPOSIT = 14;
    private static final int SLOT_SATURATION = 15;
    private static final int SLOT_LOANS = 16;
    private static final int SLOT_AI = 22;
    private static final double TAX_STEP = 0.01;

    private final Inventory inventory;
    private Info info;
    private boolean busy;

    private record Info(long free, long brokeBelow, long recoveredAt, @Nullable String resetPending) {}

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

    // worker
    private static Info info() {
        Bankruptcy.Level level = Bankruptcy.level();
        return new Info(level.free(), level.brokeBelow(), level.recoveredAt(), SeasonReset.pending());
    }


    /* render (main thread) */

    private void render() {
        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);
        inventory.setItem(SLOT_INFO, infoItem());
        inventory.setItem(SLOT_CLOSE, named(Material.BARRIER, "§cSchließen", List.of()));

        AdminSettings.NetheriteMode mode = AdminSettings.netheriteMode();
        String modeText = switch (mode) {
            case AUTO -> "§eautomatisch §7(ab Tag " + AdminSettings.NETHERITE_AUTO_DAY + ")";
            case OPEN -> "§afreigegeben";
            case CLOSED -> "§cgesperrt";
        };
        inventory.setItem(SLOT_NETHERITE, setting(Material.NETHERITE_INGOT, "§fNetherit bei Hondo: " + modeText, List.of(
                "§7Gerade: " + (Hondo.netheriteOpen() ? "§azu kaufen" : "§cnicht zu kaufen"),
                "§7Spieler sehen kein Datum, nur \"gerade nicht vorrätig\".",
                "§7Gilt auch für das Freundschaftsangebot (Stufe 5).",
                "§7Beim Saisonreset wieder automatisch."), "umschalten"));
        inventory.setItem(SLOT_NETHERITE_LIMIT, setting(Material.CLOCK, "§fNetherit pro 24 Stunden: §a" + AdminSettings.netheritePerDay(),
                List.of("§7Je Spieler, gekauft und als Angebot zusammen."), "-1 / +1"));
        inventory.setItem(SLOT_DIAMOND, setting(Material.DIAMOND, "§fDiamanten zum Saisonstart teurer: "
                        + (AdminSettings.diamondEarly() ? "§aan" : "§caus"), List.of(
                "§7Die ersten " + Hondo.DIAMOND_EARLY_DAYS + " Tage der Saison " + percent(Hondo.DIAMOND_EARLY_FACTOR - 1)
                        + " teurer bei Hondo.",
                "§7Gerade: " + (Hondo.earlyFactor(Material.DIAMOND) > 1 ? "§eAufschlag aktiv" : "§7kein Aufschlag")), "an / aus"));
        inventory.setItem(SLOT_WITHDRAW, setting(Material.PAPER, "§fSteuer Bargeld abheben: §a" + percent(AdminSettings.cashWithdrawTax()),
                taxLore(Taxes.cashWithdrawRate()), "-1 % / +1 %"));
        inventory.setItem(SLOT_DEPOSIT, setting(Material.CHEST, "§fSteuer Bargeld einzahlen: §a" + percent(AdminSettings.cashDepositTax()),
                taxLore(Taxes.cashDepositRate()), "-1 % / +1 %"));
        inventory.setItem(SLOT_SATURATION, setting(Material.HOPPER, "§fHändler gesättigt: §a" + format(AdminSettings.saturatedHours()) + " Stunden",
                List.of("§7Fällt der Ankauf einer Ware auf 0, kauft", "§7der Händler sie so lange nicht an."), "-1 / +1 Stunde"));
        inventory.setItem(SLOT_LOANS, setting(Material.EMERALD, "§fNeue Kredite bei Basil: " + (AdminSettings.loansOpen() ? "§aja" : "§cnein"),
                List.of("§7Laufende Kredite laufen weiter.", "§7Sinnvoll z. B. vor einem Saisonende",
                        "§7(ein vorgemerkter Saisonreset sperrt sie ohnehin).", "§7Beim Saisonreset wieder an."), "an / aus"));
        inventory.setItem(SLOT_AI, setting(Material.WRITABLE_BOOK, "§fKI-Marktprüfung: " + (AdminSettings.aiReview() ? "§aan" : "§caus"),
                List.of("§7Täglicher Marktbericht und Korrekturen am Katalog", "§7(braucht den API-Schlüssel in der Config)."), "an / aus"));
    }

    private ItemStack infoItem() {
        List<String> lore = new ArrayList<>();
        lore.add("§7Saison-Tag: §f" + SeasonReset.seasonDay() + " §7(seit " + SeasonReset.startDay() + ")");
        lore.add("§7Staatskasse frei: §f" + Items.format(Math.max(0, info.free())) + " Schilling");
        lore.add("§7Pleite unter §f" + Items.format(info.brokeBelow()) + "§7, erholt ab §f" + Items.format(info.recoveredAt()));
        lore.add("§7Staat: " + (Bankruptcy.isBroke() ? "§cpleite" : "§azahlungsfähig"));
        lore.add("§7Steuerfaktor: §f" + String.format(java.util.Locale.GERMANY, "%.2f", Economy.factor()) + " §7(" + Text.strip(Economy.levelWord()) + ")");
        lore.add("§7Saisonreset: " + (info.resetPending() == null ? "§fnicht vorgemerkt" : "§cvorgemerkt §7(" + info.resetPending() + ")"));
        lore.add("");
        lore.add("§e» Shift + Rechts: §fSaisonstart auf heute setzen");
        lore.add("§7(z. B. am Eröffnungstag, wenn der Server vorher");
        lore.add("§7schon zum Einrichten lief)");
        return named(Material.GOLD_INGOT, "§6Wirtschaft", lore);
    }

    private static List<String> taxLore(double effective) {
        return List.of("§7Grundwert mal Steuerfaktor, gerade §f" + percent(effective) + "§7.",
                "§7Steigt und fällt mit der Wirtschaft.");
    }

    private static ItemStack setting(Material icon, String name, List<String> description, String controls) {
        List<String> lore = new ArrayList<>(description);
        lore.add("");
        lore.add("§e» Links / Rechts: §f" + controls);
        lore.add("§e» Shift + Links: §fStandard");
        return named(icon, name, lore);
    }

    private static String format(double hours) {
        return hours == Math.rint(hours) ? String.valueOf((long) hours) : String.format(java.util.Locale.GERMANY, "%.1f", hours);
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
        // the season begins today (opening day after a setup phase)
        if (event.getRawSlot() == SLOT_INFO && click == ClickType.SHIFT_RIGHT) {
            busy = true;
            Tasks.supplyAsync(() -> {
                try {
                    SeasonReset.startToday(player.getName());
                    return info();
                } catch (RuntimeException e) {
                    return null;
                }
            }, fresh -> {
                busy = false;
                if (fresh == null) {
                    ShopView.fail(player, "§fDas ging gerade nicht, versuch es gleich nochmal.");
                    return;
                }
                info = fresh;
                player.sendMessage(xyz.jupp.minecraft.Main.getChatPrefix() + "§fDie Saison beginnt jetzt heute §7(Tag 1)§f.");
                render();
                HondoView.refreshAll();
            });
            return;
        }
        boolean reset = click == ClickType.SHIFT_LEFT;
        boolean up = click == ClickType.RIGHT;
        if (!reset && click != ClickType.LEFT && !up) return;
        int slot = event.getRawSlot();
        if (slot == SLOT_CLOSE) {
            Tasks.sync(player::closeInventory);
            return;
        }
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
            case SLOT_AI -> {
                key = AdminSettings.AI_REVIEW;
                value = AdminSettings.aiReview() ? "false" : "true";
            }
            default -> {
                return;
            }
        }
        String stored = reset ? null : value;
        busy = true;
        Tasks.supplyAsync(() -> {
            try {
                AdminSettings.set(key, stored, player.getName());
                return info();
            } catch (RuntimeException e) {
                // the database failed: nothing changed, the menu stays usable
                return null;
            }
        }, fresh -> {
            busy = false;
            if (fresh == null) {
                ShopView.fail(player, "§fDas ging gerade nicht, versuch es gleich nochmal.");
                return;
            }
            info = fresh;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            render();
            // the counters and menus show the new rules right away
            HondoView.refreshAll();
            ShopView.refreshAll();
        });
    }

    // whole percent steps between 0 and 50 %
    private static String tax(double current, boolean up) {
        double next = Math.round((current + (up ? TAX_STEP : -TAX_STEP)) * 100) / 100.0;
        return String.valueOf(Math.max(0, Math.min(0.5, next)));
    }

}
