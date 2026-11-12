package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.SeasonReset;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.ArrayList;
import java.util.List;

/**
 * /saisonreset (console and admins): marks the season start for the next server start ({@link SeasonReset}).
 * Without arguments it shows what happens and whether a reset is marked; "bestätigen" marks it, "abbrechen" removes
 * the mark.
 */
public class SeasonResetCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }
        String prefix = Main.getChatPrefix();
        String action = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (action) {
            case "bestätigen", "bestaetigen" -> Tasks.supplyAsync(() -> {
                SeasonReset.mark(sender.getName());
                return SeasonReset.pending();
            }, marked -> {
                sender.sendMessage(prefix + "§aSaisonreset vorgemerkt §7(" + marked + ")§a.");
                sender.sendMessage("§7Er läuft beim nächsten Serverstart: erst Sicherung aller Tabellen, dann Neustart der Saison.");
                sender.sendMessage("§7Starte den Server jetzt neu. Abbrechen bis dahin: §f/" + label + " abbrechen");
            });
            case "abbrechen" -> Tasks.supplyAsync(SeasonReset::cancel, removed -> sender.sendMessage(prefix + (removed
                    ? "§aSaisonreset abgebrochen, beim nächsten Start bleibt alles, wie es ist."
                    : "§7Es war kein Saisonreset vorgemerkt.")));
            default -> Tasks.supplyAsync(SeasonReset::pending, marked -> {
                List<String> lines = new ArrayList<>();
                lines.add("§8--- §6Saisonreset §8---");
                lines.add("§7Beim nächsten Serverstart beginnt alles neu, was Spieler aufgebaut haben:");
                lines.add("§fGeld, Staatskasse, Steuerhistorie, Markt-Nachfrage, Bank, Festgelder, Kredite, Anleihen, Tresore,");
                lines.add("§fHondo, Nomad, Kopfgelder, Gefängnisstrafen, Teams, Claims, Warps, Bauamt-Aufträge und");
                lines.add("§fSchadensrechnungen, Kistenshop-Verkäufe, Notverkauf, Zufallsitem.");
                lines.add("§7Bleibt: Shop-Katalog, Dienste, Bauamt-Vorlagen, Baupläne und das Register der stehenden Bauten,");
                lines.add("§7Gefängniszellen, Gesucht-Tafel, Spawn und Kartenbereiche, Befehlsprotokoll.");
                lines.add("§7Die Welt und die Inventare fasst der Reset nicht an, aber Geldscheine der alten Saison");
                lines.add("§7sind danach nichts mehr wert.");
                lines.add("§7Vorher wird jede Tabelle in ein Backup-Schema in der Datenbank kopiert.");
                lines.add(marked == null
                        ? "§eVormerken: §f/" + label + " bestätigen"
                        : "§c§lVorgemerkt §7(" + marked + ")§c. §7Abbrechen: §f/" + label + " abbrechen");
                lines.forEach(sender::sendMessage);
            });
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) return List.of();
        if (args.length != 1) return List.of();
        return List.of("bestätigen", "abbrechen").stream().filter(option -> option.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
    }

}
