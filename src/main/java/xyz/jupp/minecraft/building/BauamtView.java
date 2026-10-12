package xyz.jupp.minecraft.building;

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
import org.bukkit.inventory.meta.BookMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The Bauamt villager's menu: the treasury, the templates with their material lists, the player's open reports, the news. */
public final class BauamtView implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int SLOT_INFO = 4;
    private static final int TEMPLATES_START = 9, TEMPLATES_END = 26;
    private static final int PENDING_START = 27, PENDING_END = 35;
    private static final int SLOT_DAMAGE = 36, SLOT_RECENT = 40, SLOT_CLOSE = 44;
    private static final int LIST_LINES = 8;
    private static final int BOOK_LINES = 14;
    private static final long CLICK_COOLDOWN_MILLIS = 300;

    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Blueprint> templateSlots = new HashMap<>();
    private final Map<Integer, Build> pendingSlots = new HashMap<>();
    private boolean busy;
    private long ignoreClicksUntil;

    private BauamtView(Player viewer) {
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.section("§6§lBauamt §8» §7Bauförderung"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static void open(@NotNull Player player) {
        Tasks.supplyAsync(Bauamt::refreshFunding, funding -> {
            if (!player.isOnline()) return;
            BauamtView view = new BauamtView(player);
            view.render();
            player.openInventory(view.inventory);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_WORK_MASON, 0.8f, 1f);
        });
    }

    private void render() {
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        templateSlots.clear();
        pendingSlots.clear();
        ItemStack frame = Items.pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);

        Bauamt.Funding funding = Bauamt.funding();
        List<String> info = new ArrayList<>(List.of(
                "§7Bau eine der Vorlagen irgendwo nach.",
                "§7Beim letzten Block sagt dir das Bauamt",
                "§7Bescheid. Dann komm her und hol dir",
                "§7die Förderung aus der Staatskasse.",
                "",
                "§fStaatskasse: §a" + Items.format(funding.balance()) + " Schilling"));
        if (funding.broke()) {
            info.add("§cDer Staat ist pleite, keine Förderung.");
        } else {
            info.add("§fFörderung gerade: §a" + funding.percent() + " % §7des Richtwerts");
            if (funding.reduced()) info.add("§7Die Kasse liegt unter ihren Ø Einnahmen (" + Items.format(funding.averageIncome()) + " am Tag).");
            if (funding.reserve() > 0) info.add("§7Unter " + Items.format(funding.reserve()) + " Schilling zahlt der Staat nicht.");
        }
        info.addAll(List.of(
                "",
                "§7Fehlt der Hinweis? Setz einen Block des",
                "§7Bauwerks neu. Luft im Rahmen muss frei",
                "§7bleiben. Beschädigte Bauwerke meldet das Amt."));
        inventory.setItem(SLOT_INFO, Items.named(Material.SCAFFOLDING, "§6§lBauamt", info));

        List<Blueprint> templates = Bauamt.enabledTemplates();
        if (templates.isEmpty()) {
            inventory.setItem(TEMPLATES_START + 4, Items.named(Material.BARRIER, "§7Noch keine Vorlagen", List.of()));
        }
        for (int i = 0; i < templates.size() && TEMPLATES_START + i <= TEMPLATES_END; i++) {
            Blueprint template = templates.get(i);
            int slot = TEMPLATES_START + i;
            templateSlots.put(slot, template);
            inventory.setItem(slot, Items.named(template.icon(), "§e" + template.name(), List.of(
                    payoutLine(template, funding),
                    "§7Größe: §f" + Bauamt.size(template) + " §7Blöcke",
                    "§7Muster: bei §f" + template.originX() + " / " + template.originY() + " / " + template.originZ(),
                    "§7Bisher gebaut: §f" + Bauamt.copies(template) + "×",
                    "",
                    "§e» Linksklick: Materialliste")));
        }

        List<Build> pending = Bauamt.pending(viewer.getUniqueId());
        if (pending.isEmpty()) {
            inventory.setItem(PENDING_START + 4, Items.named(Material.MAP, "§7Keine offenen Anträge", List.of(
                    "§7Hier erscheinen deine fertigen Bauwerke.")));
        }
        for (int i = 0; i < pending.size() && PENDING_START + i <= PENDING_END; i++) {
            Build build = pending.get(i);
            Blueprint template = Bauamt.template(build.templateId());
            if (template == null) continue;
            int slot = PENDING_START + i;
            pendingSlots.put(slot, build);
            int payout = Bauamt.payout(template, funding);
            List<String> lore = new ArrayList<>();
            lore.add("§7bei §f" + build.x() + " / " + build.y() + " / " + build.z());
            lore.add(payoutLine(template, funding));
            lore.add("§7Gemeldet " + Items.ago(build.createdAt()));
            lore.add("");
            if (funding.broke()) lore.add("§cDer Staat ist pleite.");
            else if (!Bauamt.affordable(payout, funding)) lore.add("§7Die Staatskasse kann das gerade nicht zahlen.");
            else lore.add("§e» Linksklick: Abnahme & Auszahlung");
            inventory.setItem(slot, Items.glow(Items.named(Material.PAPER, "§fBauantrag: §e" + template.name(), lore), true));
        }

        List<String> damage = new ArrayList<>();
        for (Build build : Bauamt.damaged()) {
            if (damage.size() >= LIST_LINES) break;
            Blueprint template = Bauamt.template(build.templateId());
            if (template == null) continue;
            damage.add("§7• §e" + template.name() + " §7bei §f" + build.centerX() + " / " + build.centerZ()
                    + " §7(" + Items.ago(build.damagedAt()) + ")");
        }
        if (damage.isEmpty()) damage.add("§7Keine Schäden gemeldet.");
        damage.addFirst("§7Beschädigte Bauwerke, das Amt ermittelt.");
        damage.add(1, "");
        inventory.setItem(SLOT_DAMAGE, Items.named(Material.CRACKED_STONE_BRICKS, "§c§lSchadensmeldungen", damage));

        List<String> recent = new ArrayList<>();
        for (Build build : Bauamt.recent(LIST_LINES)) {
            Blueprint template = Bauamt.template(build.templateId());
            if (template == null) continue;
            recent.add("§7• §e" + template.name() + " §7von §f" + build.playerName() + " §7bei §f" + build.centerX() + " / " + build.centerZ());
        }
        if (recent.isEmpty()) recent.add("§7Noch nichts gefördert.");
        inventory.setItem(SLOT_RECENT, Items.named(Material.GOLD_INGOT, "§6§lZuletzt gefördert", recent));
        inventory.setItem(SLOT_CLOSE, Items.named(Material.BARRIER, "§cSchließen", List.of()));
    }

    // "Förderung: 500 Schilling" or, while the treasury is short, "Förderung gerade: 300 (Richtwert 500)"
    private static String payoutLine(Blueprint template, Bauamt.Funding funding) {
        if (funding.broke() || !funding.reduced()) return "§7Förderung: §a" + Items.format(template.reward()) + " Schilling";
        return "§7Förderung gerade: §a" + Items.format(Bauamt.payout(template, funding)) + " §7(Richtwert " + Items.format(template.reward()) + ")";
    }

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || busy) return;
        if (System.currentTimeMillis() < ignoreClicksUntil) return;
        if (event.getClick() != ClickType.LEFT) return;
        int slot = event.getRawSlot();
        if (slot == SLOT_CLOSE) {
            Tasks.sync(player::closeInventory);
        } else if (templateSlots.containsKey(slot)) {
            player.openBook(materialBook(templateSlots.get(slot)));
        } else if (pendingSlots.containsKey(slot)) {
            claim(player, pendingSlots.get(slot));
        }
    }

    private void claim(Player player, Build build) {
        Blueprint template = Bauamt.template(build.templateId());
        if (template == null) return;
        busy = true;
        Bauamt.claim(player, build, outcome -> {
            busy = false;
            switch (outcome) {
                case OK -> {
                    int percent = template.reward() > 0 ? (int) Math.round(100.0 * build.reward() / template.reward()) : 100;
                    player.sendMessage(Main.getChatPrefix() + "§fDas Bauamt hat deinen §e" + template.name() + " §fabgenommen: §a+"
                            + Items.format(build.reward()) + " Schilling§f." + (percent < 100 ? " §7(" + percent + " % des Richtwerts, die Kasse ist knapp)" : ""));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                }
                case BROKE -> fail(player, "§fDer Staat ist pleite, gerade gibt es keine Förderung. Der Antrag bleibt offen.");
                case EMPTY -> fail(player, "§fDie Staatskasse kann das gerade nicht zahlen. Der Antrag bleibt offen.");
                case NOT_INTACT -> fail(player, "§fDas Bauwerk bei " + build.centerX() + " / " + build.centerZ()
                        + " ist nicht mehr vollständig. Der Antrag bleibt offen.");
                case UNLOADED -> fail(player, "§fDas Bauwerk konnte gerade nicht geprüft werden, versuch es gleich nochmal.");
                case NOT_PENDING -> fail(player, "§fDieser Antrag ist nicht mehr offen.");
                default -> fail(player, "§fDas hat gerade nicht geklappt, versuch es gleich nochmal.");
            }
            if (player.getOpenInventory().getTopInventory() == inventory) render();
        });
    }

    private static void fail(Player player, String message) {
        player.sendMessage(Main.getChatPrefix() + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
    }

    // the material list, opened without giving the book; block names come from the client's language
    private static ItemStack materialBook(Blueprint template) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(template.name()).decoration(TextDecoration.BOLD, true));
        lines.add(Component.text("Richtwert: " + Items.format(template.reward()) + " Schilling"));
        lines.add(Component.text(Bauamt.size(template) + " Blöcke, Luft im Rahmen bleibt frei.", NamedTextColor.DARK_GRAY));
        lines.add(Component.empty());
        template.materials().forEach((material, count) ->
                lines.add(Component.text(count + "× ").append(Component.translatable(material))));
        List<Component> pages = new ArrayList<>();
        for (int from = 0; from < lines.size(); from += BOOK_LINES) {
            Component page = Component.empty();
            for (Component line : lines.subList(from, Math.min(lines.size(), from + BOOK_LINES))) {
                page = page.append(line).append(Component.newline());
            }
            pages.add(page);
        }
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text("Bauplan " + template.name()));
        meta.author(Component.text("Bauamt"));
        meta.pages(pages);
        book.setItemMeta(meta);
        return book;
    }

}
