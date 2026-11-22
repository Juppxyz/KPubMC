package xyz.jupp.minecraft.commands;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.HoverSpin;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * /hover <Text> puts a floating text exactly where the admin stands, at eye height (a text display); /hover item puts a
 * floating item there (an item display). The other subcommands change the nearest one around the admin's eyes: text,
 * size, facing, brightness, background, shadow, seen through walls, alignment, an item that spins, moving and removing.
 * A first word that is a subcommand never becomes a text (wrong arguments get the usage; "&r" in front writes it as a
 * text). Texts from before 11/2026 were invisible
 * marker armor stands, shifted by half a block; entfernen, hoch and runter find those as well.
 */
public class HoverTextCommand implements CommandExecutor, TabCompleter {

    public static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "hover");
    private static final double SEARCH_RADIUS = 4;
    private static final double DEFAULT_STEP = 0.25;
    private static final int LINE_WIDTH = 1_000;
    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 10f;
    // the vanilla text background: black at a quarter
    private static final Color NORMAL_BACKGROUND = Color.fromARGB(0x40, 0, 0, 0);
    private static final int DEFAULT_OPACITY = 50;

    private static final List<String> SUBCOMMANDS = List.of("hilfe", "text", "item", "entfernen", "hierher", "hoch", "runter",
            "größe", "blick", "hell", "hintergrund", "schatten", "durchwand", "ausrichtung", "drehen");
    private static final Map<String, String> USAGE = Map.ofEntries(
            Map.entry("hilfe", "/hover hilfe"),
            Map.entry("text", "/hover text <Text>"),
            Map.entry("item", "/hover item [Material]"),
            Map.entry("entfernen", "/hover entfernen"),
            Map.entry("hierher", "/hover hierher"),
            Map.entry("hoch", "/hover hoch [Blöcke]"),
            Map.entry("runter", "/hover runter [Blöcke]"),
            Map.entry("größe", "/hover größe <0.1-10>"),
            Map.entry("blick", "/hover blick frei|aufrecht|fest"),
            Map.entry("hell", "/hover hell an|aus"),
            Map.entry("hintergrund", "/hover hintergrund aus|normal|<Farbe>|#RRGGBB [Deckkraft 0-100]"),
            Map.entry("schatten", "/hover schatten an|aus"),
            Map.entry("durchwand", "/hover durchwand an|aus"),
            Map.entry("ausrichtung", "/hover ausrichtung links|mitte|rechts"),
            Map.entry("drehen", "/hover drehen an|aus"));
    private static final List<String> ON_OFF = List.of("an", "aus");
    private static final Map<String, Display.Billboard> FACINGS = Map.of(
            "frei", Display.Billboard.CENTER, "aufrecht", Display.Billboard.VERTICAL, "fest", Display.Billboard.FIXED);
    private static final Map<String, TextDisplay.TextAlignment> ALIGNMENTS = Map.of(
            "links", TextDisplay.TextAlignment.LEFT, "mitte", TextDisplay.TextAlignment.CENTER, "rechts", TextDisplay.TextAlignment.RIGHT);
    private static final Map<String, DyeColor> COLORS = colors();

    private static Map<String, DyeColor> colors() {
        Map<String, DyeColor> colors = new LinkedHashMap<>();
        colors.put("schwarz", DyeColor.BLACK);
        colors.put("grau", DyeColor.GRAY);
        colors.put("hellgrau", DyeColor.LIGHT_GRAY);
        colors.put("weiß", DyeColor.WHITE);
        colors.put("rot", DyeColor.RED);
        colors.put("orange", DyeColor.ORANGE);
        colors.put("gelb", DyeColor.YELLOW);
        colors.put("grün", DyeColor.GREEN);
        colors.put("hellgrün", DyeColor.LIME);
        colors.put("türkis", DyeColor.CYAN);
        colors.put("hellblau", DyeColor.LIGHT_BLUE);
        colors.put("blau", DyeColor.BLUE);
        colors.put("lila", DyeColor.PURPLE);
        colors.put("magenta", DyeColor.MAGENTA);
        colors.put("pink", DyeColor.PINK);
        colors.put("braun", DyeColor.BROWN);
        return colors;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur ingame nutzbar.");
            return true;
        }
        if (!PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("hilfe"))) {
            sendHelp(player);
            return true;
        }
        String first = normalize(args[0]);
        if (USAGE.containsKey(first)) {
            if (!subcommand(player, first, args)) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "§cSo geht's: §a" + USAGE.get(first)
                        + " §7(Ein Text, der mit diesem Wort anfängt: &r davor, z. B. /hover &r" + args[0] + " ...)");
                if (first.equals("hintergrund")) player.sendMessage(Main.getChatPrefix() + "§7Farben: " + String.join(", ", COLORS.keySet()));
            }
            return true;
        }

        // "&" -> §-Farben (Legacy), "\n" (as typed) starts a new line
        createTextDisplay(player, legacyText(args, 0));
        confirm(player, "Der HoverText wurde §aerfolgreich §ferstellt. §7(/hover hilfe zeigt, was sich einstellen lässt)");
        return true;
    }

    // false if the arguments do not fit the subcommand
    private boolean subcommand(Player player, String first, String[] args) {
        String second = args.length >= 2 ? normalize(args[1]) : null;
        switch (first) {
            case "entfernen" -> {
                if (args.length != 1) return false;
                Entity nearest = nearest(player, HoverTextCommand::isHoverText);
                if (nearest == null) return fail(player);
                nearest.remove();
                confirm(player, "Der HoverText wurde §aentfernt§f.");
                return true;
            }
            case "hoch", "runter" -> {
                if (args.length > 2) return false;
                double step;
                try {
                    step = args.length == 2 ? Double.parseDouble(args[1].replace(',', '.')) : DEFAULT_STEP;
                } catch (NumberFormatException e) {
                    player.sendMessage(Main.getChatPrefix() + "§cKeine Zahl: §f" + args[1]);
                    return true;
                }
                Entity nearest = nearest(player, HoverTextCommand::isHoverText);
                if (nearest == null) return fail(player);
                nearest.teleport(nearest.getLocation().add(0, first.equals("hoch") ? step : -step, 0));
                confirm(player, "Der HoverText wurde um §a" + step + " §fBlöcke " + (first.equals("hoch") ? "nach oben" : "nach unten") + " verschoben.");
                return true;
            }
            case "hierher" -> {
                if (args.length != 1) return false;
                Display display = nearestDisplay(player);
                if (display == null) return fail(player);
                Location eyes = eyePosition(player);
                eyes.setYaw(display.getYaw());
                eyes.setPitch(display.getPitch());
                display.teleport(eyes);
                confirm(player, "Der HoverText steht jetzt §agenau hier§f.");
                return true;
            }
            case "text" -> {
                if (args.length < 2) return false;
                TextDisplay display = nearest(player, TextDisplay.class);
                if (display == null) return fail(player);
                display.text(LegacyComponentSerializer.legacySection().deserialize(legacyText(args, 1)));
                confirm(player, "Der Text wurde §ageändert§f.");
                return true;
            }
            case "item" -> {
                return createItem(player, args);
            }
            case "größe" -> {
                Float scale = args.length == 2 ? number(args[1]) : null;
                if (scale == null) return false;
                if (scale < MIN_SCALE || scale > MAX_SCALE) {
                    player.sendMessage(Main.getChatPrefix() + "§cDie Größe geht von " + MIN_SCALE + " bis " + (int) MAX_SCALE + " §7(1 = normal)§c.");
                    return true;
                }
                Display display = nearestDisplay(player);
                if (display == null) return fail(player);
                Transformation now = display.getTransformation();
                display.setInterpolationDelay(0);
                display.setTransformation(new Transformation(now.getTranslation(), now.getLeftRotation(),
                        new Vector3f(scale, scale, scale), now.getRightRotation()));
                confirm(player, "Die Größe ist jetzt §a" + scale + "§f.");
                return true;
            }
            case "blick" -> {
                Display.Billboard billboard = args.length == 2 ? FACINGS.get(second) : null;
                if (billboard == null) return false;
                Display display = nearestDisplay(player);
                if (display == null) return fail(player);
                display.setBillboard(billboard);
                if (billboard == Display.Billboard.FIXED) {
                    // upright towards the admin, straight to the nearest side (like a sign on a wall)
                    float yaw = Math.round((player.getYaw() + 180f) / 90f) * 90f;
                    display.setRotation(yaw, 0);
                }
                confirm(player, switch (second) {
                    case "frei" -> "Der HoverText dreht sich jetzt §aimmer zum Spieler§f.";
                    case "aufrecht" -> "Der HoverText dreht sich jetzt §anur seitlich §fzum Spieler.";
                    default -> "Der HoverText steht jetzt §afest §fin deine Richtung.";
                });
                return true;
            }
            case "hell" -> {
                Boolean on = args.length == 2 ? onOff(second) : null;
                if (on == null) return false;
                Display display = nearestDisplay(player);
                if (display == null) return fail(player);
                display.setBrightness(on ? new Display.Brightness(15, 15) : null);
                confirm(player, on ? "Der HoverText ist jetzt §aauch im Dunkeln hell§f." : "Der HoverText hat wieder §adas Licht der Umgebung§f.");
                return true;
            }
            case "hintergrund" -> {
                return background(player, args);
            }
            case "schatten", "durchwand" -> {
                Boolean on = args.length == 2 ? onOff(second) : null;
                if (on == null) return false;
                TextDisplay display = nearest(player, TextDisplay.class);
                if (display == null) return fail(player);
                if (first.equals("schatten")) {
                    display.setShadowed(on);
                    confirm(player, "Textschatten §a" + (on ? "an" : "aus") + "§f.");
                } else {
                    display.setSeeThrough(on);
                    confirm(player, on ? "Der Text ist jetzt §aauch durch Wände §fzu sehen." : "Der Text ist §anicht mehr durch Wände §fzu sehen.");
                }
                return true;
            }
            case "ausrichtung" -> {
                TextDisplay.TextAlignment alignment = args.length == 2 ? ALIGNMENTS.get(second) : null;
                if (alignment == null) return false;
                TextDisplay display = nearest(player, TextDisplay.class);
                if (display == null) return fail(player);
                display.setAlignment(alignment);
                confirm(player, "Die Zeilen stehen jetzt §a" + second + "§f.");
                return true;
            }
            case "drehen" -> {
                Boolean on = args.length == 2 ? onOff(second) : null;
                if (on == null) return false;
                ItemDisplay display = nearest(player, ItemDisplay.class);
                if (display == null) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                    player.sendMessage(Main.getChatPrefix() + "§cIm Umkreis von " + (int) SEARCH_RADIUS + " Blöcken ist kein schwebendes Item. §7(Drehen geht nur bei Items.)");
                    return true;
                }
                HoverSpin.set(display, on);
                confirm(player, on ? "Das Item §adreht sich §fjetzt." : "Das Item §asteht §fwieder still.");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // /hover item [Material]: the given item or one of the item in the hand
    private boolean createItem(Player player, String[] args) {
        if (args.length > 2) return false;
        ItemStack item;
        if (args.length == 2) {
            Material material = Material.matchMaterial(args[1]);
            if (material == null || !material.isItem() || material.isAir()) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "§cKein Item: §f" + args[1] + " §7(z. B. diamond, oak_log)");
                return true;
            }
            item = new ItemStack(material);
        } else {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
                player.sendMessage(Main.getChatPrefix() + "§cNimm das Item in die Hand §7(oder: /hover item <Material>)§c.");
                return true;
            }
            item = hand.asOne();
        }
        player.getWorld().spawn(eyePosition(player), ItemDisplay.class, display -> {
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            // turns sideways towards every viewer; /hover drehen an makes it spin instead
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setPersistent(true);
            display.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        });
        confirm(player, "Das schwebende Item wurde §aerstellt§f. §7(/hover drehen an, /hover größe 2 ...)");
        return true;
    }

    // /hover hintergrund aus|normal|<Farbe>|#RRGGBB [Deckkraft in %]
    private boolean background(Player player, String[] args) {
        if (args.length < 2 || args.length > 3) return false;
        String choice = normalize(args[1]);
        Color color;
        String described;
        if (choice.equals("aus") && args.length == 2) {
            color = Color.fromARGB(0, 0, 0, 0);
            described = "§aaus";
        } else if (choice.equals("normal") && args.length == 2) {
            color = NORMAL_BACKGROUND;
            described = "§awie normal";
        } else {
            Color rgb = rgb(choice);
            if (rgb == null) return false;
            Float opacity = args.length == 3 ? number(args[2].replace("%", "")) : Float.valueOf(DEFAULT_OPACITY);
            if (opacity == null || opacity < 0 || opacity > 100) return false;
            color = rgb.setAlpha(Math.round(opacity * 2.55f));
            described = "§a" + args[1] + " §7(" + Math.round(opacity) + " % deckend)";
        }
        TextDisplay display = nearest(player, TextDisplay.class);
        if (display == null) return fail(player);
        display.setDefaultBackground(false);
        display.setBackgroundColor(color);
        confirm(player, "Hintergrund " + described + "§f.");
        return true;
    }

    private static @Nullable Color rgb(String choice) {
        DyeColor dye = COLORS.get(choice);
        if (dye != null) return dye.getColor();
        if (choice.matches("#[0-9a-f]{6}")) return Color.fromRGB(Integer.parseInt(choice.substring(1), 16));
        return null;
    }

    // exactly at the admin's position and eye height, facing every viewer; the old version added half a block to x and z
    private void createTextDisplay(Player player, String legacyTitle) {
        player.getWorld().spawn(eyePosition(player), TextDisplay.class, display -> {
            display.text(LegacyComponentSerializer.legacySection().deserialize(legacyTitle));
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            // only a typed \n breaks the line, like the old armor stand names (the default wraps at 200 px)
            display.setLineWidth(LINE_WIDTH);
            display.setPersistent(true);
            display.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private static Location eyePosition(Player player) {
        Location at = player.getLocation();
        return new Location(at.getWorld(), at.getX(), at.getY() + player.getEyeHeight(), at.getZ());
    }

    private static String legacyText(String[] args, int from) {
        return Text.amp(String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length))).replace("\\n", "\n");
    }


    /* finding the nearest one around the admin's eyes */

    private static @Nullable Entity nearest(Player player, Predicate<Entity> filter) {
        Location eyes = player.getEyeLocation();
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : player.getWorld().getNearbyEntities(eyes, SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS, filter)) {
            double distance = entity.getLocation().distanceSquared(eyes);
            if (distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static <T extends Display> @Nullable T nearest(Player player, Class<T> type) {
        return type.cast(nearest(player, entity -> type.isInstance(entity) && isOurs(entity)));
    }

    // a text or an item from this command (not an old armor stand)
    private static @Nullable Display nearestDisplay(Player player) {
        return (Display) nearest(player, entity -> entity instanceof Display && isOurs(entity));
    }

    private static boolean isOurs(Entity entity) {
        return entity.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    // a display from this command or an old marker armor stand
    private static boolean isHoverText(Entity entity) {
        if (entity instanceof Display) return isOurs(entity);
        return entity instanceof ArmorStand stand && stand.isMarker() && !stand.isVisible() && stand.isCustomNameVisible();
    }


    /* small helpers */

    // lower case, ae/oe/ue/ss as typed without umlauts
    private static String normalize(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "groesse", "grösse" -> "größe";
            case "weiss" -> "weiß";
            case "gruen" -> "grün";
            case "hellgruen" -> "hellgrün";
            case "tuerkis" -> "türkis";
            default -> lower;
        };
    }

    private static @Nullable Boolean onOff(@Nullable String word) {
        if ("an".equals(word)) return true;
        if ("aus".equals(word)) return false;
        return null;
    }

    private static @Nullable Float number(String text) {
        try {
            float value = Float.parseFloat(text.replace(',', '.'));
            return Float.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean fail(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
        player.sendMessage(Main.getChatPrefix() + "§cIm Umkreis von " + (int) SEARCH_RADIUS + " Blöcken ist kein passender HoverText.");
        return true;
    }

    private static void confirm(Player player, String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
        player.sendMessage(Main.getChatPrefix() + message);
    }

    private static void sendHelp(Player player) {
        player.sendMessage("§8§m------------§r §6HoverText §8§m------------");
        player.sendMessage("§a/hover <Text> §7- neuer Text, genau wo du stehst §8(§7&-Farben, \\n = neue Zeile§8)");
        player.sendMessage("§a/hover item §8[§aMaterial§8] §7- schwebendes Item §8(§7sonst das aus deiner Hand§8)");
        player.sendMessage("§fFür den nächsten HoverText §7(bis " + (int) SEARCH_RADIUS + " Blöcke um deinen Kopf)§8:");
        player.sendMessage("§a/hover text <Text> §7- Text ändern");
        player.sendMessage("§a/hover entfernen §8| §ahierher §8| §ahoch§8/§arunter §8[§aBlöcke§8]");
        player.sendMessage("§a/hover größe <0.1-10> §7- Größe §8(§71 = normal§8)");
        player.sendMessage("§a/hover blick frei§8|§aaufrecht§8|§afest §7- zum Spieler, nur seitlich, oder fest in deine Richtung");
        player.sendMessage("§a/hover hell an§8|§aaus §7- auch im Dunkeln voll hell");
        player.sendMessage("§a/hover hintergrund aus§8|§anormal§8|§a<Farbe>§8|§a#RRGGBB §8[§aDeckkraft %§8]");
        player.sendMessage("§a/hover schatten an§8|§aaus §8| §adurchwand an§8|§aaus §8| §aausrichtung links§8|§amitte§8|§arechts");
        player.sendMessage("§a/hover drehen an§8|§aaus §7- das Item dreht sich");
        player.sendMessage("§7Farben: " + String.join(", ", COLORS.keySet()));
    }


    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player) || !PermissionsUtil.isPlayerAdmin(player)) return List.of();
        if (args.length == 1) return startingWith(SUBCOMMANDS, args[0]);
        String first = normalize(args[0]);
        if (args.length == 2) {
            return switch (first) {
                case "hell", "schatten", "durchwand", "drehen" -> startingWith(ON_OFF, args[1]);
                case "blick" -> startingWith(List.of("frei", "aufrecht", "fest"), args[1]);
                case "ausrichtung" -> startingWith(List.of("links", "mitte", "rechts"), args[1]);
                case "größe" -> startingWith(List.of("0.5", "1", "1.5", "2", "3"), args[1]);
                case "hoch", "runter" -> startingWith(List.of("0.1", "0.25", "0.5", "1"), args[1]);
                case "hintergrund" -> {
                    List<String> options = new ArrayList<>(List.of("aus", "normal"));
                    options.addAll(COLORS.keySet());
                    yield startingWith(options, args[1]);
                }
                case "item" -> {
                    if (args[1].length() < 2) yield List.of();
                    yield java.util.Arrays.stream(Material.values()).filter(material -> material.isItem() && !material.isAir() && !material.isLegacy())
                            .map(material -> material.name().toLowerCase(Locale.ROOT))
                            .filter(name -> name.startsWith(args[1].toLowerCase(Locale.ROOT))).limit(40).toList();
                }
                default -> List.of();
            };
        }
        if (args.length == 3 && first.equals("hintergrund") && rgb(normalize(args[1])) != null) {
            return startingWith(List.of("25", "50", "75", "100"), args[2]);
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
