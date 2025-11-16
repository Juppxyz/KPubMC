package xyz.jupp.minecraft.commands;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;

public class HoverTextCommand implements CommandExecutor {

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
            player.sendMessage(Main.getChatPrefix() + "Bitte verwende: §a/hover <Text>");
            return true;
        }

        // "&" -> §-Farben (Legacy), anschließend in Adventure-Component wandeln
        String raw = String.join(" ", args);
        String legacy = ChatColor.translateAlternateColorCodes('&', raw);

        createNewArmorStand(player.getLocation(), legacy);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
        player.sendMessage(Main.getChatPrefix() + "Der HoverText wurde §aerfolgreich §ferstellt.");
        return true;
    }

    private ArmorStand createNewArmorStand(Location loc, String legacyTitle) {
        Location pos = loc.clone().add(0.5, 1.0, 0.5);

        ArmorStand as = (ArmorStand) pos.getWorld().spawnEntity(pos, EntityType.ARMOR_STAND);
        as.setGravity(false);
        as.setVisible(false);
        as.setInvulnerable(true);
        as.setMarker(true);
        as.setSmall(true);
        as.setBasePlate(false);
        as.setCustomNameVisible(true);

        as.customName(LegacyComponentSerializer.legacySection().deserialize(legacyTitle));
        return as;
    }
}
