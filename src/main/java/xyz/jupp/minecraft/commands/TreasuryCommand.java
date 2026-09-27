package xyz.jupp.minecraft.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
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

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Instant now = Instant.now();
        Tasks.supplyAsync(() -> {
            Map<Treasury.Source, Long> day = Treasury.inflowSince(now.minus(Duration.ofDays(1)));
            Map<Treasury.Source, Long> week = Treasury.inflowSince(now.minus(Duration.ofDays(7)));
            List<String> lines = new ArrayList<>();
            lines.add("§8=-- §6§lStaatskasse §8--=");
            lines.add("§fStand: " + Main.getCurrencyName((int) Math.min(Integer.MAX_VALUE, Treasury.balance())));
            lines.add("§fEinnahmen §8(24 h / 7 Tage)§8:");
            for (Treasury.Source source : Treasury.Source.values()) {
                lines.add("§8» §7" + source.label() + "§8: §a" + day.getOrDefault(source, 0L) + " §8/ §a" + week.getOrDefault(source, 0L));
            }
            lines.add("§7Handelssteuer aktuell: §a" + Math.round(Taxes.tradeRate() * 100) + "%");
            return lines;
        }, lines -> lines.forEach(sender::sendMessage));
        return true;
    }

}
