package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legacy-Texte (§-Codes) als Components, exakt so wie Paper sie in den alten String-Methoden speichert und liefert.
 */
public final class Text {

    private static final Pattern LEGACY = Pattern.compile("(§[0-9a-fk-orx])|((?:(?:https?):\\/\\/)?(?:[-\\w_\\.]{2,}\\.[a-z]{2,4}.*?(?=[\\.\\?!,;:]?(?:[§ \\n]|$))))|(\\n)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRIP = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private static final String COLOR_CODES = "0123456789abcdef";
    private static final NamedTextColor[] COLORS = {
            NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA,
            NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY,
            NamedTextColor.DARK_GRAY, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA,
            NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE};
    private static final String FORMAT_CODES = "lonmk";
    private static final TextDecoration[] FORMATS = {
            TextDecoration.BOLD, TextDecoration.ITALIC, TextDecoration.UNDERLINED,
            TextDecoration.STRIKETHROUGH, TextDecoration.OBFUSCATED};
    private static final Style RESET = Style.style()
            .decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.UNDERLINED, false).decoration(TextDecoration.STRIKETHROUGH, false)
            .decoration(TextDecoration.OBFUSCATED, false).build();
    private static final Style NOT_ITALIC = Style.style().decoration(TextDecoration.ITALIC, false).build();

    private Text() {
    }

    /** Wie ItemMeta#setDisplayName, sendActionBar(String) und createInventory(holder, type, String): null bei null oder "", nur bis zum ersten Zeilenumbruch. */
    public static Component of(String legacy) {
        if (legacy == null || legacy.isEmpty()) return null;
        List<Component> parts = new ArrayList<>();
        Style style = Style.empty();
        StringBuilder hex = null;
        boolean hasReset = false;
        boolean needsAdd = false;
        int current = 0;
        Matcher matcher = LEGACY.matcher(legacy);
        while (matcher.find()) {
            int group = matcher.group(1) != null ? 1 : matcher.group(2) != null ? 2 : 3;
            int index = matcher.start(group);
            if (index > current) {
                needsAdd = false;
                parts.add(Component.text(legacy.substring(current, index), style));
                current = index;
            }
            if (group == 1) {
                char code = Character.toLowerCase(legacy.charAt(index + 1));
                if (code == 'x') {
                    hex = new StringBuilder();
                } else if (hex != null) {
                    hex.append(code);
                    if (hex.length() == 6) {
                        style = RESET.color(hexColor(hex.toString()));
                        hex = null;
                    }
                } else if (FORMAT_CODES.indexOf(code) >= 0) {
                    style = style.decoration(FORMATS[FORMAT_CODES.indexOf(code)], true);
                } else {
                    Style previous = style;
                    style = (hasReset ? NOT_ITALIC : RESET).color(code == 'r' ? null : COLORS[COLOR_CODES.indexOf(code)]);
                    hasReset = true;
                    for (TextDecoration format : FORMATS) {
                        if (previous.decoration(format) == TextDecoration.State.TRUE) style = style.decoration(format, false);
                    }
                }
                needsAdd = true;
            } else if (group == 2) {
                String url = matcher.group(2);
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
                int end = matcher.end(2);
                parts.add(Component.text(legacy.substring(current, end), isUri(url) ? style.clickEvent(ClickEvent.openUrl(url)) : style));
                current = end;
            } else {
                if (needsAdd) parts.add(Component.text("", style));
                return Component.text().append(parts).build();
            }
            current = matcher.end(group);
        }
        if (current < legacy.length() || needsAdd) parts.add(Component.text(legacy.substring(current), style));
        return Component.text().append(parts).build();
    }

    /** Wie Entity#setCustomName(String): kürzt auf 256 Zeichen, sonst wie {@link #of(String)}. */
    public static Component entityName(String legacy) {
        return of(legacy != null && legacy.length() > 256 ? legacy.substring(0, 256) : legacy);
    }

    /** Wie Player#setPlayerListName(String): null (Standard-Tabname) bei null oder dem eigenen Spielernamen. */
    public static Component listName(String legacy, String playerName) {
        return legacy == null || legacy.equals(playerName) ? null : of(legacy);
    }

    /** Wie ItemMeta#setLore(List): null bei null oder leerer Liste, leere Zeile bei null oder "". */
    public static List<Component> lore(List<String> legacy) {
        if (legacy == null || legacy.isEmpty()) return null;
        List<Component> lore = new ArrayList<>(legacy.size());
        for (String line : legacy) {
            Component component = of(line);
            lore.add(component != null ? component : Component.empty());
        }
        return lore;
    }

    /** Wie ItemMeta#getDisplayName() und Player#getPlayerListName(): "" bei null. */
    public static String legacy(Component component) {
        if (component == null) return "";
        StringBuilder out = new StringBuilder();
        appendLegacy(component, out, false);
        return out.toString();
    }

    /** Wie Entity#getCustomName(): null bei null. */
    public static String legacyOrNull(Component component) {
        return component == null ? null : legacy(component);
    }

    /** Wie ItemMeta#getLore(): null bei null. */
    public static List<String> legacyLore(List<Component> lore) {
        if (lore == null) return null;
        List<String> legacy = new ArrayList<>(lore.size());
        for (Component line : lore) legacy.add(legacy(line));
        return legacy;
    }

    /** Wie broadcastMessage, Player#setDisplayName, Titel von createInventory(holder, size, String) und Schild-Zeilen: null bei null. */
    public static Component section(String legacy) {
        return legacy == null ? null : LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    /** Wie InventoryView#getTitle(), Sign#getLine(int) und SignChangeEvent#getLine(int): null bei null. */
    public static String section(Component component) {
        return component == null ? null : LegacyComponentSerializer.legacySection().serialize(component);
    }

    /** Wie ChatColor.stripColor(String). */
    public static String strip(String legacy) {
        return legacy == null ? null : STRIP.matcher(legacy).replaceAll("");
    }

    /** Wie ChatColor.translateAlternateColorCodes('&', String). */
    public static String amp(String text) {
        if (text == null) throw new IllegalArgumentException("Cannot translate null text");
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(chars[i + 1]) > -1) {
                chars[i] = '§';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    private static boolean appendLegacy(Component component, StringBuilder out, boolean hadFormat) {
        Style style = component.style();
        TextColor color = style.color();
        String content = component instanceof TextComponent text ? text.content()
                : PlainTextComponentSerializer.plainText().serialize(component.children(List.of()));
        if (!(component instanceof TextComponent) || !content.isEmpty() || color != null) {
            if (color != null) {
                out.append(legacyColor(color));
                hadFormat = true;
            } else if (hadFormat) {
                out.append("§r");
                hadFormat = false;
            }
        }
        for (int i = 0; i < FORMATS.length; i++) {
            if (style.decoration(FORMATS[i]) == TextDecoration.State.TRUE) {
                out.append('§').append(FORMAT_CODES.charAt(i));
                hadFormat = true;
            }
        }
        out.append(content);
        for (Component child : component.children()) hadFormat = appendLegacy(child, out, hadFormat);
        return hadFormat;
    }

    private static String legacyColor(TextColor color) {
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i].value() == color.value()) return "§" + COLOR_CODES.charAt(i);
        }
        StringBuilder out = new StringBuilder("§x");
        for (char digit : String.format(Locale.ROOT, "%06X", color.value()).toCharArray()) out.append('§').append(digit);
        return out.toString();
    }

    private static TextColor hexColor(String hex) {
        for (int i = 0; i < hex.length(); i++) {
            if (COLOR_CODES.indexOf(hex.charAt(i)) < 0) return null;
        }
        return TextColor.color(Integer.parseInt(hex, 16));
    }

    private static boolean isUri(String url) {
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
