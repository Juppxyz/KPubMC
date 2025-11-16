package xyz.jupp.minecraft.commands;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

public class CreateDummyEntityCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl ist nur für Spieler.");
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage(Main.getChatPrefix() + "§cDas darfst du nicht.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Main.getChatPrefix() + "§fBitte verwende: §a/dummy <TYPE> <ATTR...> <NAME>");
            return true;
        }

        EntityType entityType;
        try {
            entityType = EntityType.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Main.getChatPrefix() + "§cUnbekannter EntityType: §f" + args[0]);
            return true;
        }

        Location location = player.getLocation();
        Entity entity = player.getWorld().spawnEntity(location, entityType);

        boolean jumping = false;
        boolean sneaking = false;
        boolean customName = false;

        for (String arg : args) {
            if (arg.equalsIgnoreCase("jumping")) {
                jumping = true;
            }
            if (arg.equalsIgnoreCase("sneaking")) {
                sneaking = true;
            }
            if (arg.equalsIgnoreCase("customname")
                    || arg.equalsIgnoreCase("customName")
                    || arg.equalsIgnoreCase("name")) {
                customName = true;
            }
        }

        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setGravity(false);
            living.setCollidable(false);
            living.setInvulnerable(true);

            if (jumping) {
                living.setJumping(true);
            }
            if (sneaking) {
                living.setSneaking(true);
            }
            if (customName) {
                living.setCustomNameVisible(true);
                living.setCustomName(args[args.length - 1]);
            }
        } else {
            player.sendMessage(Main.getChatPrefix() + "§cDieser Entity-Typ hat keine AI.");
        }

        player.sendMessage(Main.getChatPrefix() + "§aDummy-Entity gespawnt: §f" + entityType.name());
        return true;
    }
}
