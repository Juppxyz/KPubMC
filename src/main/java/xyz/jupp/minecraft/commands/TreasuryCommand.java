package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.economy.TaxClass;
import xyz.jupp.minecraft.economy.Bank;
import xyz.jupp.minecraft.economy.Bankruptcy;
import xyz.jupp.minecraft.economy.Bonds;
import xyz.jupp.minecraft.economy.Economy;
import xyz.jupp.minecraft.economy.Loans;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.economy.Treasury;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.TabListUtil;
import xyz.jupp.minecraft.utils.Tasks;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// /staatskasse: balance and inflows of the state treasury
public class TreasuryCommand implements CommandExecutor {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.");

    private static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // admins: the state starts over (not founded, not broke), e.g. on a test server
        if (args.length == 1 && "neustart".equalsIgnoreCase(args[0])
                && (!(sender instanceof Player player) || PermissionsUtil.isPlayerAdmin(player))) {
            Tasks.supplyAsync(() -> {
                Bankruptcy.reset();
                return Main.getChatPrefix() + "§aDer Staat startet neu: nicht gegründet, nicht pleite. §7Gegründet wird er wieder ab "
                        + "der Erholungsgrenze, oder 7 Tage nach der ersten Buchung, wenn die Kasse dann über der Pleite-Grenze liegt.";
            }, message -> {
                sender.sendMessage(message);
                TabListUtil.updateTabForAll();
            });
            return true;
        }
        Instant now = Instant.now();
        Tasks.supplyAsync(() -> {
            Map<Treasury.Source, Long> day = Treasury.inflowSince(now.minus(Duration.ofDays(1)));
            Map<Treasury.Source, Long> week = Treasury.inflowSince(now.minus(Duration.ofDays(7)));
            List<String> lines = new ArrayList<>();
            lines.add("§8=-- §6§lStaatskasse §8--=");
            if (Bankruptcy.isBroke()) {
                Bankruptcy.Level level = Bankruptcy.level();
                lines.add("§4§lDer Staat ist pleite! §cSteuern erhöht.");
                lines.add("§7Erholt sich ab §f" + level.recoveredAt() + " Schilling §7frei, jetzt §f" + Math.max(0, level.free())
                        + " §7(Pleite-Grenze " + level.brokeBelow() + ")");
                lines.add("§7Hilf mit: Notverkauf im Shop, Staatsanleihen bei Basil.");
            }
            long owed = Bank.owedDeposits();
            lines.add("§fStand: " + Main.getCurrencyName((int) Math.min(Integer.MAX_VALUE, Treasury.balance()))
                    + (owed > 0 ? " §7(davon " + owed + " Festgeld der Spieler)" : ""));
            long today = Treasury.income(day);
            long lastWeek = Treasury.income(week);
            lines.add("§fEingenommen: §a" + today + " §7heute§8, §a" + lastWeek + " §7diese Woche");
            long interest = -week.getOrDefault(Treasury.Source.INTEREST, 0L);
            if (interest > 0) lines.add("§fZinsen an Sparer: §c" + interest + " §7diese Woche");
            long grants = -week.getOrDefault(Treasury.Source.BUILDING_GRANT, 0L);
            if (grants > 0) lines.add("§fBauförderung: §c" + grants + " §7diese Woche");
            long bounties = -week.getOrDefault(Treasury.Source.BOUNTY, 0L);
            if (bounties > 0) lines.add("§fKopfgelder: §c" + bounties + " §7diese Woche");
            long lent = Loans.lentOut();
            if (lent > 0) lines.add("§fAn Spieler verliehen: §e" + lent + " Schilling §7(Kredite)");
            long bonds = Bonds.owed();
            if (bonds > 0) lines.add("§fStaatsanleihen offen: §c" + bonds + " Schilling");
            lines.add("§fSteuern gerade: " + Economy.levelWord());
            lines.add("§8» §7Essen & Farm-Sachen: §a" + percent(Taxes.rate(TaxClass.BASIC))
                    + " §8| §7Normale Waren: §a" + percent(Taxes.rate(TaxClass.STANDARD))
                    + " §8| §7Seltenes: §a" + percent(Taxes.rate(TaxClass.LUXURY)));
            lines.add("§8» §7Sterben: §a" + percent(Taxes.deathRate()) + " §8| §7Nether-Portal: §abis " + percent(Taxes.topNetherRate()));
            Economy.Review report = Economy.latestReport();
            if (report != null) lines.add("§fMarktbericht §7(" + DATE.format(report.day()) + ")§f: §7§o" + report.text());
            return lines;
        }, lines -> lines.forEach(sender::sendMessage));
        return true;
    }

}
