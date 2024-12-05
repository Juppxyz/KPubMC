package xyz.jupp.minecraft.commands;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class HelpCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player) {
            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                Player player = (Player)sender;
                player.playSound(player.getLocation(), Sound.ENTITY_EGG_THROW, 2f,2f);
                player.sendMessage(Main.getChatPrefix() + "Hier siehst du alle Befehle§8:");
                player.sendMessage("§8§l» §a/regeln §8- §fZeigt das aktuelle Regelwerk an.");
                player.sendMessage("§8§l» §a/warp §8- §fZeigt dir das Warp Menü an.");
                player.sendMessage("§8§l» §a/team §8- §fZeigt dir euer Team Menü an.");
                player.sendMessage("§8§l» §a@ <Nachricht> §8- §fPrivater TeamChat");
                player.sendMessage("§8§l» §a/invites §8- §fÄndert, ob du offen für Team-Anfragen bist.");
                player.sendMessage("§8§l» §a/slimechunk §8- §fÜberprüft ob du in einem SlimeChunk bist.");
                player.sendMessage("§8§l» §a/money §8- §fRuft dein Konto auf.");
                player.sendMessage("§8§l» §a/ranking §8- §fZeigt das Team Ranking an.");
                player.sendMessage("§8§l» §a/head <Spielername> §8- §fKauft den Kopf des Spielers. (100 Schilling)");
                player.sendMessage("§8§l» §a/donate §8- §fZeigt dir den Link zum freiwilligen Spenden an.");
                player.sendMessage("§8§l» §a/spawn §8- §fTeleportiert dich zum Spawn.");

                if (PermissionsUtil.isPlayerAdmin(player)) {
                    player.sendMessage(" ");
                    player.sendMessage("§cAdmin§8» ");
                    //player.sendMessage("§8§l» §a/exil [Name] §8- §fVerbannt einen Spieler ins Exil.");
                    player.sendMessage("§8§l» §a/config §8- §fLade die Config + Shop neu. ");
                    player.sendMessage("§8§l» §a/spec §8- §fSei Undercover!");
                    player.sendMessage("§8§l» §a/hover <Text ..> §8- §fErstellt einen neuen HoverText");
                    player.sendMessage("§8§l» §a/createshop §8- §fErstellt einen neuen Villager Händler.");
                    player.sendMessage("§8§l» §a/createbankier §8- §fErstellt einen neuen Finanz Händler.");
                    //player.sendMessage("§8§l» §a/acnotify §8- §fSteuert die AntiCheat Meldungen.");
                    return;
                }
                if (PermissionsUtil.isPlayerMod(player)){
                    player.sendMessage("§8§l» §a/acnotify §8- §fSteuert die AntiCheat Meldungen.");
                    return;
                }

            });
        }
        return false;

    }
}

