package xyz.jupp.minecraft.team;

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
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Founding a team (/team neu <Name>): pick a colour, then found it for CREATION_COST Schilling.
 * Going alone (/team allein): pick a title, then found a team of one for SOLO_COST Schilling.
 * Only used on the main thread.
 */
public final class TeamCreateView implements InventoryHolder {

    // letters (with umlauts), digits, _ and -
    public static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_-]{" + Teams.NAME_MIN + "," + Teams.NAME_MAX + "}");

    private record Colour(String code, String label) {}

    // §a is left out: green names mean "no team"; §7 and §8 belong to the titles, §0 reads badly
    private static final List<Colour> COLOURS = List.of(
            new Colour("§4", "Rot"), new Colour("§c", "Hellrot"), new Colour("§6", "Gold"), new Colour("§e", "Gelb"),
            new Colour("§2", "Dunkelgrün"), new Colour("§b", "Aqua"), new Colour("§3", "Türkis"), new Colour("§1", "Dunkelblau"),
            new Colour("§9", "Blau"), new Colour("§d", "Pink"), new Colour("§5", "Lila"), new Colour("§f", "Weiß"));
    private static final int[] COLOUR_SLOTS = {10, 12, 14, 16, 19, 21, 23, 25, 28, 30, 32, 34};

    private record TitleOption(Teams.Title title, Material icon, String motto) {}

    private static final List<TitleOption> TITLES = List.of(
            new TitleOption(Teams.Title.SOELDNER, Material.IRON_SWORD, "Kämpft für den, der zahlt."),
            new TitleOption(Teams.Title.HAENDLER, Material.EMERALD, "Kauft billig, verkauft teuer."),
            new TitleOption(Teams.Title.VAGABUND, Material.LEATHER_BOOTS, "Zieht umher, gehört niemandem."));
    private static final int[] TITLE_SLOTS = {20, 22, 24};

    private static final int SLOT_NAME = 4;
    private static final int SLOT_CONFIRM = 40;
    private static final int SLOT_CLOSE = 44;
    private static final long CLICK_COOLDOWN_MILLIS = 300;

    // null: going alone, the title is the name
    private final @Nullable String name;
    private final String playerName;
    private final Inventory inventory;
    private @Nullable String colour;
    private @Nullable Teams.Title title;
    private boolean busy;
    private long ignoreClicksUntil;

    private TeamCreateView(@Nullable String name, String playerName) {
        this.name = name;
        this.playerName = playerName;
        this.inventory = org.bukkit.Bukkit.createInventory(this, 45, Text.of(name == null ? "§aAllein losziehen" : "§aTeam gründen"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Main thread, after the checks of /team neu. */
    public static void open(@NotNull Player player, @NotNull String name) {
        show(player, new TeamCreateView(name, player.getName()));
    }

    /** Main thread, after the checks of /team allein. */
    public static void openSolo(@NotNull Player player) {
        show(player, new TeamCreateView(null, player.getName()));
    }

    private static void show(Player player, TeamCreateView view) {
        view.render();
        player.openInventory(view.inventory);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
    }

    private void render() {
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        ItemStack frame = Items.pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, frame);
        if (name == null) {
            renderTitles();
        } else {
            renderColours(name);
        }
        inventory.setItem(SLOT_CLOSE, Items.named(Material.BARRIER, "§cAbbrechen", List.of()));
    }

    private void renderColours(String name) {
        String shown = (colour == null ? "§7" : colour) + name;
        inventory.setItem(SLOT_NAME, Items.named(Material.NAME_TAG, shown, List.of(
                colour == null ? "§7Wähle unten eine Farbe." : "§7So sieht euer Name aus.")));
        for (int i = 0; i < COLOURS.size(); i++) {
            Colour option = COLOURS.get(i);
            boolean selected = option.code().equals(colour);
            inventory.setItem(COLOUR_SLOTS[i], Items.glow(Items.named(colourBlock(option.code()), option.code() + option.label(),
                    List.of(selected ? "§a✔ Ausgewählt" : "§e» Klicken zum Auswählen")), selected));
        }
        inventory.setItem(SLOT_CONFIRM, colour == null
                ? Items.named(Material.GRAY_DYE, "§7Erst eine Farbe wählen", List.of())
                : Items.named(Material.NETHER_STAR, "§a§lTeam gründen", List.of(
                        "§7Kostet §f" + Items.format(Teams.CREATION_COST) + " Schilling",
                        "",
                        "§e» Klicken zum Gründen")));
    }

    private void renderTitles() {
        String shown = title == null ? "§7Wähle unten einen Titel." : "§8[" + title.colour() + title.label() + "§8] " + title.colour() + playerName;
        inventory.setItem(SLOT_NAME, Items.named(Material.NAME_TAG, shown, List.of(
                title == null ? "§7Er steht im Chat vor deinem Namen." : "§7So stehst du im Chat.",
                "",
                "§7Ein Team nur für dich: Kasse, Gebiet,",
                "§7Level und Warps, aber ohne Mitglieder.",
                "§7Der Titel ändert nur Name und Farbe.")));
        for (int i = 0; i < TITLES.size(); i++) {
            TitleOption option = TITLES.get(i);
            boolean selected = option.title() == title;
            inventory.setItem(TITLE_SLOTS[i], Items.glow(Items.named(option.icon(), option.title().colour() + "§l" + option.title().label(), List.of(
                    "§7" + option.motto(),
                    "",
                    selected ? "§a✔ Ausgewählt" : "§e» Klicken zum Auswählen")), selected));
        }
        inventory.setItem(SLOT_CONFIRM, title == null
                ? Items.named(Material.GRAY_DYE, "§7Erst einen Titel wählen", List.of())
                : Items.named(Material.NETHER_STAR, "§a§lAls " + title.label() + " losziehen", List.of(
                        "§7Kostet §f" + Items.format(Teams.SOLO_COST) + " Schilling",
                        "",
                        "§e» Klicken zum Gründen")));
    }

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
        int[] options = name == null ? TITLE_SLOTS : COLOUR_SLOTS;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == slot) {
                if (name == null) {
                    title = TITLES.get(i).title();
                } else {
                    colour = COLOURS.get(i).code();
                }
                player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 1f, 1.4f);
                render();
                return;
            }
        }
        if (slot == SLOT_CONFIRM && (name == null ? title != null : colour != null)) found(player);
    }

    private void found(Player player) {
        if (player.getLevel() < Teams.CREATION_LEVEL) {
            fail(player, "Du brauchst §a" + Teams.CREATION_LEVEL + " §fLevel, um " + (name == null ? "allein loszuziehen." : "ein Team zu gründen."));
            return;
        }
        Teams.Title chosenTitle = name == null ? title : null;
        String chosenName = chosenTitle == null ? name : chosenTitle.label();
        String chosen = chosenTitle == null ? colour : chosenTitle.colour();
        int cost = chosenTitle == null ? Teams.CREATION_COST : Teams.SOLO_COST;
        busy = true;
        Tasks.async(() -> {
            String problem;
            try {
                if (chosenTitle == null && Teams.nameTaken(chosenName)) {
                    problem = "Den Namen §e" + chosenName + " §fgibt es schon.";
                } else {
                    // price, team and owner in one transaction: nothing to give back if it fails
                    problem = switch (CacheHandler.getInstance().createNewTeam(player, chosenName, chosen, chosenTitle, cost)) {
                        case OK -> null;
                        case IN_TEAM -> "Du bist schon in einem Team.";
                        case NO_MONEY -> (chosenTitle == null ? "Das Gründen" : "Allein loszuziehen") + " kostet " + Main.getCurrencyName(cost) + "§f.";
                        case NAME_TAKEN -> "Den Namen §e" + chosenName + " §fgibt es schon.";
                    };
                }
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Team {} could not be founded: {}", chosenName, e.toString());
                problem = "Das ging gerade nicht, versuch es gleich nochmal.";
            }
            String failure = problem;
            MainThread.run(() -> {
                busy = false;
                if (!player.isOnline()) return;
                if (failure != null) {
                    fail(player, failure);
                    return;
                }
                player.closeInventory();
                player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1.4f);
                player.sendMessage(Main.getChatPrefix() + (chosenTitle == null
                        ? "Du hast das Team " + chosen + chosenName + " §fgegründet! §7Alles Weitere mit §a/team§7."
                        : "Du bist jetzt " + chosen + chosenName + "§f! §7Kasse, Gebiet und Level findest du unter §a/team§7."));
                JailHandler.refreshPlayerName(player, CacheHandler.getInstance().getPlayerInCache(player));
            });
        });
    }

    private static void fail(Player player, String message) {
        player.sendMessage(Main.getChatPrefix() + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
    }

    /** The wool (or similar) block of a team colour, also used for the race standings at Nomad. */
    public static Material colourBlock(@NotNull String teamColour) {
        return COLOUR_BLOCKS.getOrDefault(teamColour, Material.LIME_WOOL);
    }

    // §7 and §8 are the colours of the titles
    private static final Map<String, Material> COLOUR_BLOCKS = Map.ofEntries(
            Map.entry("§4", Material.RED_WOOL), Map.entry("§c", Material.RED_TERRACOTTA), Map.entry("§6", Material.ORANGE_WOOL),
            Map.entry("§e", Material.YELLOW_WOOL), Map.entry("§2", Material.GREEN_WOOL), Map.entry("§b", Material.LIGHT_BLUE_WOOL),
            Map.entry("§3", Material.CYAN_WOOL), Map.entry("§1", Material.BLUE_WOOL), Map.entry("§9", Material.BLUE_WOOL),
            Map.entry("§d", Material.PINK_WOOL), Map.entry("§5", Material.PURPLE_WOOL), Map.entry("§f", Material.WHITE_WOOL),
            Map.entry("§7", Material.LIGHT_GRAY_WOOL), Map.entry("§8", Material.GRAY_WOOL));

    /** The banner of a team colour. */
    public static Material colourBanner(@NotNull String teamColour) {
        return switch (teamColour) {
            case "§1", "§9" -> Material.BLUE_BANNER;
            case "§2" -> Material.GREEN_BANNER;
            case "§3" -> Material.CYAN_BANNER;
            case "§4", "§c" -> Material.RED_BANNER;
            case "§5" -> Material.PURPLE_BANNER;
            case "§6" -> Material.ORANGE_BANNER;
            case "§7" -> Material.LIGHT_GRAY_BANNER;
            case "§8" -> Material.GRAY_BANNER;
            case "§b" -> Material.LIGHT_BLUE_BANNER;
            case "§d" -> Material.PINK_BANNER;
            case "§e" -> Material.YELLOW_BANNER;
            case "§f" -> Material.WHITE_BANNER;
            default -> Material.LIME_BANNER;
        };
    }

}
