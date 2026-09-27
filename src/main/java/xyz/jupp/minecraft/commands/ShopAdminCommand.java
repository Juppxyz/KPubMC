package xyz.jupp.minecraft.commands;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.DatabaseException;
import xyz.jupp.minecraft.economy.Category;
import xyz.jupp.minecraft.economy.Market;
import xyz.jupp.minecraft.economy.MarketItem;
import xyz.jupp.minecraft.economy.MarketRepository;
import xyz.jupp.minecraft.economy.ShopView;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * /shopadmin: adjust the market catalog in game (values can also be edited in the database, then /shopadmin reload).
 */
public class ShopAdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("info", "set", "add", "remove", "rotate", "reset", "reload");

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) {
            PermissionsUtil.sendNoPermMsg(player);
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> {
                Material material = material(sender, args, 1);
                if (material != null) info(sender, material);
            }
            case "set" -> set(sender, args);
            case "add" -> add(sender, args);
            case "remove" -> {
                Material material = material(sender, args, 1);
                if (material != null) change(sender, () -> MarketRepository.update(material, "enabled", false), "§f" + material + " ist deaktiviert.");
            }
            case "rotate" -> run(sender, () -> "§fNeue Tagesangebote: §a" + Market.rotate());
            case "reset" -> {
                if (args.length < 2) {
                    help(sender);
                    return true;
                }
                Material material = args[1].equalsIgnoreCase("alle") ? null : material(sender, args, 1);
                if (material == null && !args[1].equalsIgnoreCase("alle")) return true;
                change(sender, () -> {
                    MarketRepository.resetDemand(material);
                    return true;
                }, "§fNachfrage zurückgesetzt.");
            }
            case "reload" -> change(sender, () -> true, "§fKatalog neu geladen.");
            default -> help(sender);
        }
        return true;
    }

    private static void help(CommandSender sender) {
        sender.sendMessage(Main.getChatPrefix() + "§fShop-Verwaltung§8:");
        sender.sendMessage("§8» §a/shopadmin info <Material>");
        sender.sendMessage("§8» §a/shopadmin set <Material> <Feld> <Wert> §8(Felder: " + String.join(", ", MarketRepository.EDITABLE_COLUMNS.keySet()) + ")");
        sender.sendMessage("§8» §a/shopadmin add <Material> <Kategorie> <Preis> [Menge]");
        sender.sendMessage("§8» §a/shopadmin remove <Material> §8- deaktiviert das Item");
        sender.sendMessage("§8» §a/shopadmin rotate §8- neue Tagesangebote ziehen");
        sender.sendMessage("§8» §a/shopadmin reset <Material|alle> §8- Nachfrage zurücksetzen");
        sender.sendMessage("§8» §a/shopadmin reload §8- nach Änderungen in der Datenbank");
    }

    private static void info(CommandSender sender, Material material) {
        Tasks.supplyAsync(() -> {
            Market.reload();
            MarketItem item = Market.get(material);
            if (item == null) return List.of(Main.getChatPrefix() + "§c" + material + " ist nicht im Katalog.");
            List<String> lines = new ArrayList<>();
            lines.add("§8=-- §a" + material + " §8--=");
            lines.add("§fKategorie: §a" + item.category() + " §8| §fFest: §a" + yes(item.core()) + " §8| §fAktiv: §a" + yes(item.enabled()));
            lines.add("§fMenge: §a" + item.amount() + " §8| §fKaufbar: §a" + yes(item.buyable()) + " §8| §fVerkaufbar: §a" + yes(item.sellable()));
            lines.add("§fBasispreis: §a" + item.basePrice() + " §8| §fMin/Max: §a" + item.effectiveMinPrice() + "§8/§a" + item.effectiveMaxPrice());
            lines.add("§fElastizität: §a" + item.elasticity() + " §8| §fAnkaufquote: §a" + item.sellRatio() + " §8| §fGewicht: §a" + item.rotationWeight());
            lines.add("§fNachfrage: §a" + String.format(Locale.ROOT, "%.2f", item.demand())
                    + " §8| §fKauf: §a" + item.buyPrice() + " §8| §fAnkauf: §a" + item.sellPrice());
            for (long[] volume : MarketRepository.volumeSince(material, 7)) {
                lines.add("§f7 Tage " + (volume[0] == 1 ? "gekauft" : "verkauft") + ": §a" + volume[1] + " Stück §8(" + volume[2] + " Schilling)");
            }
            return lines;
        }, lines -> lines.forEach(sender::sendMessage));
    }

    private static void set(CommandSender sender, String[] args) {
        if (args.length < 4) {
            help(sender);
            return;
        }
        Material material = material(sender, args, 1);
        if (material == null) return;
        String column = MarketRepository.EDITABLE_COLUMNS.get(args[2].toLowerCase(Locale.ROOT));
        if (column == null) {
            sender.sendMessage(Main.getChatPrefix() + "§cUnbekanntes Feld. §7Erlaubt: " + String.join(", ", MarketRepository.EDITABLE_COLUMNS.keySet()));
            return;
        }
        String raw = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        Object value;
        try {
            value = parse(column, raw);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Main.getChatPrefix() + "§cUngültiger Wert: §7" + e.getMessage());
            return;
        }
        change(sender, () -> MarketRepository.update(material, column, value), "§f" + material + ": §a" + args[2] + " §f= §a" + raw);
    }

    private static @Nullable Object parse(String column, String raw) {
        boolean none = raw.equals("-") || raw.equalsIgnoreCase("auto");
        return switch (column) {
            case "min_price", "max_price" -> none ? null : Integer.parseInt(raw);
            case "base_price", "amount", "rotation_weight" -> Integer.parseInt(raw);
            case "sell_ratio", "elasticity" -> Double.parseDouble(raw.replace(',', '.'));
            case "core", "enabled", "buyable", "sellable" -> switch (raw.toLowerCase(Locale.ROOT)) {
                case "ja", "true", "an" -> true;
                case "nein", "false", "aus" -> false;
                default -> throw new IllegalArgumentException("ja/nein erwartet");
            };
            case "category" -> {
                Category category = Category.parse(raw);
                if (category == null) throw new IllegalArgumentException("Kategorien: " + Arrays.toString(Category.values()));
                yield category.name();
            }
            case "display_name", "description" -> none ? null : raw.replace('&', '§');
            default -> throw new IllegalArgumentException(column);
        };
    }

    private static void add(CommandSender sender, String[] args) {
        if (args.length < 4) {
            help(sender);
            return;
        }
        Material material = material(sender, args, 1);
        Category category = Category.parse(args[2]);
        if (material == null) return;
        if (category == null) {
            sender.sendMessage(Main.getChatPrefix() + "§cKategorien: §7" + Arrays.toString(Category.values()));
            return;
        }
        int price;
        int amount;
        try {
            price = Integer.parseInt(args[3]);
            amount = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        } catch (NumberFormatException e) {
            sender.sendMessage(Main.getChatPrefix() + "§cPreis und Menge müssen Zahlen sein.");
            return;
        }
        MarketItem item = new MarketItem(material, category, null, null, amount, price, null, null,
                Market.defaultElasticity(category), 0.5, true, false, true, 1, true, 0);
        change(sender, () -> MarketRepository.insert(item), "§f" + material + " ist im Katalog §8(verkaufbar per set ... verkaufbar ja)§f.");
    }

    private static @Nullable Material material(CommandSender sender, String[] args, int index) {
        Material material = args.length > index ? Material.matchMaterial(args[index]) : null;
        if (material == null || !material.isItem()) {
            sender.sendMessage(Main.getChatPrefix() + "§cUnbekanntes Material.");
            return null;
        }
        return material;
    }

    // database change on a worker, then the cache is reloaded and every open shop re-rendered
    private static void change(CommandSender sender, Supplier<Boolean> change, String success) {
        run(sender, () -> {
            if (!change.get()) return "§cNichts geändert (nicht im Katalog oder schon vorhanden).";
            Market.reload();
            return success;
        });
    }

    private static void run(CommandSender sender, Supplier<String> work) {
        Tasks.supplyAsync(() -> {
            try {
                return work.get();
            } catch (DatabaseException e) {
                return "§cDatenbank-Fehler: §7" + e.getMessage();
            }
        }, message -> {
            sender.sendMessage(Main.getChatPrefix() + message);
            ShopView.refreshAll();
        });
    }

    private static String yes(boolean value) {
        return value ? "ja" : "nein";
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !PermissionsUtil.isPlayerAdmin(player)) return List.of();
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (args.length) {
            case 1 -> SUBCOMMANDS;
            case 2 -> Market.all().stream().map(item -> item.material().name().toLowerCase(Locale.ROOT)).sorted().toList();
            case 3 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "set" -> List.copyOf(MarketRepository.EDITABLE_COLUMNS.keySet());
                case "add" -> Arrays.stream(Category.values()).map(Enum::name).toList();
                default -> List.of();
            };
            default -> List.of();
        };
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(current)).toList();
    }

}
