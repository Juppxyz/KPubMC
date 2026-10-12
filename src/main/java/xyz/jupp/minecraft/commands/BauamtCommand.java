package xyz.jupp.minecraft.commands;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.building.Bauamt;
import xyz.jupp.minecraft.building.Blueprint;
import xyz.jupp.minecraft.database.DatabaseException;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * /bauamt: the admin side of the Bauamt. Two corners are picked by looking at a block (pos1, pos2), the box between
 * them becomes a template; the selection is trimmed to its blocks when it is saved.
 */
public class BauamtCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("pos1", "pos2", "vorlage", "liste", "an", "aus", "belohnung", "löschen", "topf");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_-]{2,24}");
    private static final int REACH = 64;
    private static final int MAX_EDGE = Blueprint.MAX_EDGE;
    private static final int MAX_CELLS = Blueprint.MAX_CELLS;
    private static final int MIN_BLOCKS = Blueprint.MIN_BLOCKS;

    // per admin: the two corners, kept until the next restart
    private static final Map<UUID, Location[]> selections = new HashMap<>();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur im Spiel.");
            return true;
        }
        if (!PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }
        if (args.length == 0) {
            help(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "pos1" -> corner(player, 0);
            case "pos2" -> corner(player, 1);
            case "vorlage" -> create(player, args);
            case "liste" -> list(player);
            case "an" -> enable(player, args, true);
            case "aus" -> enable(player, args, false);
            case "belohnung" -> reward(player, args);
            case "löschen", "loeschen" -> delete(player, args);
            case "topf" -> Tasks.supplyAsync(Bauamt::refreshPot, pot ->
                    player.sendMessage(Main.getChatPrefix() + "§fFördertopf heute: §a" + Items.format(pot) + " Schilling"));
            default -> help(player);
        }
        return true;
    }

    private static void help(Player player) {
        player.sendMessage(Main.getChatPrefix() + "§6§lBauamt §7(Admin)");
        player.sendMessage("§8» §a/bauamt pos1 §8| §apos2 §8- §fEcke: der Block, den du anschaust");
        player.sendMessage("§8» §a/bauamt vorlage <Name> <Belohnung> §8- §fAuswahl als Vorlage speichern");
        player.sendMessage("§8» §a/bauamt liste §8- §fAlle Vorlagen");
        player.sendMessage("§8» §a/bauamt an|aus <Name> §8- §fVorlage freigeben oder sperren");
        player.sendMessage("§8» §a/bauamt belohnung <Name> <Betrag> §8- §fBelohnung ändern");
        player.sendMessage("§8» §a/bauamt löschen <Name> §8- §fVorlage samt Bauten entfernen");
        player.sendMessage("§8» §a/bauamt topf §8- §fFördertopf von heute");
        player.sendMessage("§7Luft im Rahmen muss beim Nachbau frei bleiben; der Rahmen wird beim Speichern eng um die Blöcke gezogen.");
    }

    private static void corner(Player player, int index) {
        Block target = player.getTargetBlockExact(REACH);
        if (target == null) {
            fail(player, "§fKein Block im Blick §7(bis " + REACH + " Blöcke)§f.");
            return;
        }
        Location[] corners = selections.computeIfAbsent(player.getUniqueId(), uuid -> new Location[2]);
        corners[index] = target.getLocation();
        player.sendMessage(Main.getChatPrefix() + "§fPosition " + (index + 1) + ": §e" + target.getX() + " / " + target.getY() + " / " + target.getZ());
    }

    private static void create(Player player, String[] args) {
        if (args.length < 3) {
            fail(player, "§fSo: §a/bauamt vorlage <Name> <Belohnung>");
            return;
        }
        String name = args[1];
        Integer reward = positive(args[2]);
        if (!NAME.matcher(name).matches()) {
            fail(player, "§fDer Name: 2 bis 24 Zeichen, Buchstaben, Zahlen, - und _.");
            return;
        }
        if (reward == null) {
            fail(player, "§fDie Belohnung muss eine Zahl über 0 sein.");
            return;
        }
        if (Bauamt.template(name) != null) {
            fail(player, "§fDie Vorlage §e" + name + " §fgibt es schon.");
            return;
        }
        Location[] corners = selections.get(player.getUniqueId());
        if (corners == null || corners[0] == null || corners[1] == null) {
            fail(player, "§fErst beide Ecken wählen: §a/bauamt pos1 §fund §a/bauamt pos2§f.");
            return;
        }
        World world = corners[0].getWorld();
        if (world == null || !world.equals(corners[1].getWorld())) {
            fail(player, "§fBeide Ecken müssen in derselben Welt liegen.");
            return;
        }
        int sizeX = Math.abs(corners[0].getBlockX() - corners[1].getBlockX()) + 1;
        int sizeY = Math.abs(corners[0].getBlockY() - corners[1].getBlockY()) + 1;
        int sizeZ = Math.abs(corners[0].getBlockZ() - corners[1].getBlockZ()) + 1;
        if (sizeX > MAX_EDGE || sizeY > MAX_EDGE || sizeZ > MAX_EDGE) {
            fail(player, "§fZu groß: höchstens " + MAX_EDGE + " Blöcke je Kante.");
            return;
        }
        Blueprint.Capture capture = Blueprint.capture(world, corners[0].getBlockX(), corners[0].getBlockY(), corners[0].getBlockZ(),
                corners[1].getBlockX(), corners[1].getBlockY(), corners[1].getBlockZ());
        if (capture == null || capture.blocks().size() < MIN_BLOCKS) {
            fail(player, "§fZu wenig drin: mindestens " + MIN_BLOCKS + " Blöcke.");
            return;
        }
        if ((long) capture.sizeX() * capture.sizeY() * capture.sizeZ() > MAX_CELLS) {
            fail(player, "§fZu groß: höchstens " + MAX_CELLS + " Felder im Rahmen.");
            return;
        }
        Tasks.async(() -> {
            String result;
            try {
                Blueprint template = Bauamt.create(name, reward, world, capture, player);
                result = "§fVorlage §e" + template.name() + " §fgespeichert: §7" + Bauamt.size(template) + " Blöcke, "
                        + template.blockCount() + " davon gesetzt" + (template.propCount() > 0 ? " plus " + template.propCount() + " Kissen" : "")
                        + ", " + template.variants().size() + " Drehungen, Belohnung "
                        + Items.format(reward) + " Schilling. Das Muster hier ist als Bauwerk eingetragen.";
            } catch (DatabaseException e) {
                result = "§cSpeichern fehlgeschlagen: §7" + e.getMessage();
            }
            String message = result;
            Tasks.sync(() -> player.sendMessage(Main.getChatPrefix() + message));
        });
    }

    private static void list(Player player) {
        List<Blueprint> templates = Bauamt.templates();
        player.sendMessage(Main.getChatPrefix() + "§fVorlagen: §7" + templates.size() + " §8| §fFördertopf heute: §a" + Items.format(Bauamt.pot()));
        for (Blueprint template : templates) {
            player.sendMessage("§8» " + (template.enabled() ? "§e" : "§7") + template.name() + " §8- §a" + Items.format(template.reward())
                    + " §7(" + Bauamt.size(template) + ", " + template.blockCount() + " Blöcke, " + Bauamt.copies(template) + "× gebaut"
                    + (template.enabled() ? "" : ", gesperrt") + ") §7bei " + template.originX() + " / " + template.originY() + " / " + template.originZ());
        }
    }

    private static void enable(Player player, String[] args, boolean enabled) {
        Blueprint template = named(player, args);
        if (template == null) return;
        Tasks.async(() -> {
            Bauamt.setEnabled(template, enabled);
            Tasks.sync(() -> player.sendMessage(Main.getChatPrefix() + "§fVorlage §e" + template.name() + (enabled ? " §fist freigegeben." : " §fist gesperrt.")));
        });
    }

    private static void reward(Player player, String[] args) {
        Blueprint template = named(player, args);
        if (template == null) return;
        Integer reward = args.length > 2 ? positive(args[2]) : null;
        if (reward == null) {
            fail(player, "§fSo: §a/bauamt belohnung <Name> <Betrag>");
            return;
        }
        Tasks.async(() -> {
            Bauamt.setReward(template, reward);
            Tasks.sync(() -> player.sendMessage(Main.getChatPrefix() + "§fVorlage §e" + template.name() + "§f: Belohnung jetzt §a" + Items.format(reward) + " Schilling§f."));
        });
    }

    private static void delete(Player player, String[] args) {
        Blueprint template = named(player, args);
        if (template == null) return;
        Tasks.async(() -> {
            Bauamt.delete(template);
            Tasks.sync(() -> player.sendMessage(Main.getChatPrefix() + "§fVorlage §e" + template.name() + " §fund ihre Bauten sind gelöscht."));
        });
    }

    private static @Nullable Blueprint named(Player player, String[] args) {
        if (args.length < 2) {
            fail(player, "§fWelche Vorlage? Namen siehst du mit §a/bauamt liste§f.");
            return null;
        }
        Blueprint template = Bauamt.template(args[1]);
        if (template == null) fail(player, "§fDie Vorlage §e" + args[1] + " §fgibt es nicht.");
        return template;
    }

    private static @Nullable Integer positive(String text) {
        try {
            int value = Integer.parseInt(text);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void fail(Player player, String message) {
        player.sendMessage(Main.getChatPrefix() + message);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("an", "aus", "belohnung", "löschen", "loeschen").contains(args[0].toLowerCase(Locale.ROOT))) {
            return Bauamt.templates().stream().map(Blueprint::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

}
