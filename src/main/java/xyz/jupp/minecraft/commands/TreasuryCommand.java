package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.economy.TaxClass;
import xyz.jupp.minecraft.economy.Bank;
import xyz.jupp.minecraft.economy.Economy;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.economy.Treasury;
import xyz.jupp.minecraft.utils.Tasks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// /staatskasse: balance and inflows of the state treasury
public class TreasuryCommand implements CommandExecutor {

    private static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }

    // everything that came in; fixed deposits are owed to the players and the interest went out
    private static long income(Map<Treasury.Source, Long> inflow) {
        return inflow.entrySet().stream()
                .filter(entry -> entry.getKey() != Treasury.Source.INTEREST && entry.getKey() != Treasury.Source.FIXED_DEPOSIT)
                .mapToLong(Map.Entry::getValue).sum();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Instant now = Instant.now();
        Tasks.supplyAsync(() -> {
            Map<Treasury.Source, Long> day = Treasury.inflowSince(now.minus(Duration.ofDays(1)));
            Map<Treasury.Source, Long> week = Treasury.inflowSince(now.minus(Duration.ofDays(7)));
            List<String> lines = new ArrayList<>();
            lines.add("§8=-- §6§lStaatskasse §8--=");
            long owed = Bank.owedDeposits();
            lines.add("§fStand: " + Main.getCurrencyName((int) Math.min(Integer.MAX_VALUE, Treasury.balance()))
                    + (owed > 0 ? " §8(davon " + owed + " Festgeld der Spieler)" : ""));
            long today = income(day);
            long lastWeek = income(week);
            lines.add("§fEingenommen: §a" + today + " §7heute§8, §a" + lastWeek + " §7diese Woche");
            long interest = -week.getOrDefault(Treasury.Source.INTEREST, 0L);
            if (interest > 0) lines.add("§fZinsen an Sparer: §c" + interest + " §7diese Woche");
            lines.add("§fSteuern gerade: " + Economy.levelWord());
            lines.add("§8» §7Essen & Farm-Sachen: §a" + percent(Taxes.rate(TaxClass.BASIC))
                    + " §8| §7Normale Waren: §a" + percent(Taxes.rate(TaxClass.STANDARD))
                    + " §8| §7Seltenes: §a" + percent(Taxes.rate(TaxClass.LUXURY)));
            lines.add("§8» §7Sterben: §a" + percent(Taxes.deathRate()) + " §8| §7Nether-Portal: §abis " + percent(Taxes.topNetherRate()));
            String report = Economy.latestAiSummary();
            if (report != null) lines.add("§fMarktbericht: §7§o" + report);
            return lines;
        }, lines -> lines.forEach(sender::sendMessage));
        return true;
    }

}
