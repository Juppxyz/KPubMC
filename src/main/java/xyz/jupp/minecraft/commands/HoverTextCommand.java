package xyz.jupp.minecraft.commands;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Text;

/**
 * /hover <Text> puts a floating text exactly where the admin stands, at eye height (a text display). /hover entfernen
 * removes the nearest one, /hover hoch|runter [Blöcke] moves it. Texts from before 11/2026 were invisible marker armor
 * stands, shifted by half a block; entfernen, hoch and runter find those as well.
 */
public class HoverTextCommand implements CommandExecutor {

    private static final NamespacedKey KEY = new NamespacedKey(Main.getInstance(), "hover");
    private static final double SEARCH_RADIUS = 4;
    private static final double DEFAULT_STEP = 0.25;
    private static final int LINE_WIDTH = 1_000;

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
        if (args.length == 0) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
            player.sendMessage(Main.getChatPrefix() + "Bitte verwende: §a/hover <Text> §7(&-Farben, \\n für eine neue Zeile)");
            player.sendMessage(Main.getChatPrefix() + "§a/hover entfernen §7oder §a/hover hoch|runter [Blöcke] §7für den nächsten Text");
            return true;
        }

        String first = args[0].toLowerCase();
        if (args.length == 1 && first.equals("entfernen")) {
            Entity nearest = nearest(player);
            if (nearest == null) return fail(player);
            nearest.remove();
            confirm(player, "Der HoverText wurde §aentfernt§f.");
            return true;
        }
        if (args.length <= 2 && (first.equals("hoch") || first.equals("runter"))) {
            double step;
            try {
                step = args.length == 2 ? Double.parseDouble(args[1].replace(',', '.')) : DEFAULT_STEP;
            } catch (NumberFormatException e) {
                player.sendMessage(Main.getChatPrefix() + "§cKeine Zahl: §f" + args[1]);
                return true;
            }
            Entity nearest = nearest(player);
            if (nearest == null) return fail(player);
            nearest.teleport(nearest.getLocation().add(0, first.equals("hoch") ? step : -step, 0));
            confirm(player, "Der HoverText wurde um §a" + step + " §fBlöcke " + (first.equals("hoch") ? "nach oben" : "nach unten") + " verschoben.");
            return true;
        }

        // "&" -> §-Farben (Legacy), "\n" (as typed) starts a new line
        String legacy = Text.amp(String.join(" ", args)).replace("\\n", "\n");
        createTextDisplay(player, legacy);
        confirm(player, "Der HoverText wurde §aerfolgreich §ferstellt.");
        return true;
    }

    // exactly at the admin's position and eye height, facing every viewer; the old version added half a block to x and z
    private void createTextDisplay(Player player, String legacyTitle) {
        Location at = player.getLocation();
        Location pos = new Location(at.getWorld(), at.getX(), at.getY() + player.getEyeHeight(), at.getZ());
        pos.getWorld().spawn(pos, TextDisplay.class, display -> {
            display.text(LegacyComponentSerializer.legacySection().deserialize(legacyTitle));
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            // only a typed \n breaks the line, like the old armor stand names (the default wraps at 200 px)
            display.setLineWidth(LINE_WIDTH);
            display.setPersistent(true);
            display.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // the closest hover text around the admin's eyes: a display from this command or an old marker armor stand
    private static @Nullable Entity nearest(Player player) {
        Location eyes = player.getEyeLocation();
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : player.getWorld().getNearbyEntities(eyes, SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS, HoverTextCommand::isHoverText)) {
            double distance = entity.getLocation().distanceSquared(eyes);
            if (distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean isHoverText(Entity entity) {
        if (entity instanceof TextDisplay display) return display.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
        return entity instanceof ArmorStand stand && stand.isMarker() && !stand.isVisible() && stand.isCustomNameVisible();
    }

    private static boolean fail(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
        player.sendMessage(Main.getChatPrefix() + "§cIm Umkreis von " + (int) SEARCH_RADIUS + " Blöcken ist kein HoverText.");
        return true;
    }

    private static void confirm(Player player, String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
        player.sendMessage(Main.getChatPrefix() + message);
    }
}
