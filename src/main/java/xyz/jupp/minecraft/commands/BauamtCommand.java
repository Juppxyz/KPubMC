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
import xyz.jupp.minecraft.building.Build;
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

    private static final List<String> SUBCOMMANDS = List.of("pos1", "pos2", "vorlage", "liste", "info", "prüfen", "an", "aus", "belohnung", "löschen", "stand");
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
            case "info" -> info(player, args);
            case "prüfen", "pruefen" -> diagnose(player, args);
            case "an" -> enable(player, args, true);
            case "aus" -> enable(player, args, false);
            case "belohnung" -> reward(player, args);
            case "löschen", "loeschen" -> delete(player, args);
            case "stand" -> Tasks.supplyAsync(Bauamt::refreshFunding, funding ->
                    player.sendMessage(Main.getChatPrefix() + "§fStaatskasse: §a" + Items.format(funding.balance()) + " §8| §fØ Einnahmen: §a"
                            + Items.format(funding.averageIncome()) + " §7am Tag §8| §fFörderung: §a" + funding.percent() + " % §7des Richtwerts"
                            + (funding.broke() ? " §c(Pleite: nichts)" : " §7(Reserve " + Items.format(funding.reserve()) + ")")));
            default -> help(player);
        }
        return true;
    }

    private static void help(Player player) {
        player.sendMessage(Main.getChatPrefix() + "§6§lBauamt §7(Admin)");
        player.sendMessage("§8» §a/bauamt pos1 §8| §apos2 §8- §fEcke: der Block, den du anschaust");
        player.sendMessage("§8» §a/bauamt vorlage <Name> <Richtwert> §8- §fAuswahl als Vorlage speichern");
        player.sendMessage("§8» §a/bauamt liste §8- §fAlle Vorlagen");
        player.sendMessage("§8» §a/bauamt info <Name> §8- §fMaterialliste einer Vorlage");
        player.sendMessage("§8» §a/bauamt prüfen [Name] §8- §fWarum der angeschaute Nachbau nicht passt");
        player.sendMessage("§8» §a/bauamt an|aus <Name> §8- §fVorlage freigeben oder sperren");
        player.sendMessage("§8» §a/bauamt belohnung <Name> <Betrag> §8- §fRichtwert ändern");
        player.sendMessage("§8» §a/bauamt löschen <Name> §8- §fVorlage samt Bauten entfernen");
        player.sendMessage("§8» §a/bauamt stand §8- §fStaatskasse, Ø Einnahmen und aktuelle Förderquote");
        player.sendMessage("§7Der Richtwert wird voll gezahlt, solange die Staatskasse ihre Ø Tageseinnahmen hält, darunter anteilig weniger.");
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
            fail(player, "§fSo: §a/bauamt vorlage <Name> <Richtwert>");
            return;
        }
        String name = args[1];
        Integer reward = positive(args[2]);
        if (!NAME.matcher(name).matches()) {
            fail(player, "§fDer Name: 2 bis 24 Zeichen, Buchstaben, Zahlen, - und _.");
            return;
        }
        if (reward == null) {
            fail(player, "§fDer Richtwert muss eine Zahl über 0 sein.");
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
                        + ", " + template.variants().size() + " Drehungen, Richtwert "
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
        Bauamt.Funding funding = Bauamt.funding();
        player.sendMessage(Main.getChatPrefix() + "§fVorlagen: §7" + templates.size() + " §8| §fFörderung gerade: §a" + funding.percent() + " % §7des Richtwerts");
        for (Blueprint template : templates) {
            player.sendMessage("§8» " + (template.enabled() ? "§e" : "§7") + template.name() + " §8- §a" + Items.format(template.reward())
                    + " §7Richtwert (" + Bauamt.size(template) + ", " + template.blockCount() + " Blöcke, " + Bauamt.copies(template) + "× gebaut"
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

    private static void info(Player player, String[] args) {
        Blueprint template = named(player, args);
        if (template == null) return;
        player.sendMessage(Main.getChatPrefix() + "§e" + template.name() + " §7(" + Bauamt.size(template) + ", Rahmen ab "
                + template.originX() + " / " + template.originY() + " / " + template.originZ() + ", " + template.variants().size() + " Drehungen)");
        template.materials().forEach((material, count) ->
                player.sendMessage("§8» §f" + count + "× §7" + material.name().toLowerCase(Locale.ROOT)));
        player.sendMessage("§7Alles andere im Rahmen muss beim Nachbau leer sein.");
    }

    // the best fitting placement around the block the admin looks at, and what is wrong with it
    private static void diagnose(Player player, String[] args) {
        Blueprint only = null;
        if (args.length > 1) {
            only = Bauamt.template(args[1]);
            if (only == null) {
                fail(player, "§fDie Vorlage §e" + args[1] + " §fgibt es nicht.");
                return;
            }
        }
        Block target = player.getTargetBlockExact(REACH);
        if (target == null) {
            fail(player, "§fSchau einen Block des Nachbaus an.");
            return;
        }
        Bauamt.Diagnosis diagnosis = Bauamt.diagnose(target.getWorld(), target.getX(), target.getY(), target.getZ(), only);
        if (diagnosis == null) {
            fail(player, "§fDer Block §7" + target.getType().name().toLowerCase(Locale.ROOT) + " §fkommt in keiner Vorlage vor. Schau einen Block des Bauwerks an, nicht den Boden.");
            return;
        }
        Blueprint.Variant variant = diagnosis.variant();
        player.sendMessage(Main.getChatPrefix() + "§fBeste Übereinstimmung: §e" + diagnosis.template().name() + " §7(" + variant.rotation().name().toLowerCase(Locale.ROOT)
                + (variant.mirrored() ? ", gespiegelt" : "") + ") §fRahmen ab §e" + diagnosis.ox() + " / " + diagnosis.oy() + " / " + diagnosis.oz()
                + " §8| §fFalsch: " + (diagnosis.bad() == 0 ? "§a0" : "§c" + diagnosis.bad()) + " §7von " + variant.cells() + " Zellen");
        for (String line : diagnosis.lines()) player.sendMessage("§8» §7" + line);
        if (diagnosis.overlapping() != null) {
            Build other = diagnosis.overlapping();
            player.sendMessage("§8» §7Überschneidet sich mit Bauwerk " + other.id() + " von " + other.playerName() + " (" + other.state().name().toLowerCase(Locale.ROOT) + ").");
        }
        int orders = Bauamt.openOrders(player.getUniqueId(), diagnosis.template().id());
        player.sendMessage("§8» §7Deine offenen Aufträge dafür: " + orders + ". "
                + (diagnosis.bad() == 0 ? "Ein Block neu gesetzt meldet das Bauwerk." : "Erst die Abweichungen beheben."));
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
        if (args.length == 2 && List.of("an", "aus", "belohnung", "löschen", "loeschen", "info", "prüfen", "pruefen").contains(args[0].toLowerCase(Locale.ROOT))) {
            return Bauamt.templates().stream().map(Blueprint::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

}
