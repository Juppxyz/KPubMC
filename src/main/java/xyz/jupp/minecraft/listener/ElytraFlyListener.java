package xyz.jupp.minecraft.listener;

import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.PermissionsUtil;
import java.util.Objects;

public class ElytraFlyListener implements Listener {

    @EventHandler
    public void onRoundFlightSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (!PermissionsUtil.isPlayerAdmin(player)) return;
        if (!Objects.equals(event.getLine(0), "[Rundflug]")) return;
        event.setLine(0, "§5§lRundflug");
    }

    @EventHandler
    public void onPlayerInteractWithElytraShield(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        boolean isAdmin = PermissionsUtil.isPlayerAdmin(player);

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;
        if (!(clickedBlock.getState() instanceof Sign)) return;
        if (isAdmin && event.getAction().isRightClick()) return;

        Sign sign = (Sign) clickedBlock.getState();

        String firstLine = sign.getLine(0);
        if (firstLine.isEmpty()) return;
        if (!firstLine.equals("§5§lRundflug")) return;

        player.setAllowFlight(true);
        new BukkitRunnable() {
            @Override
            public void run() {
                player.setAllowFlight(false);
                PotionEffect slowFalling = new PotionEffect(PotionEffectType.SPEED, 10*3, 2, false, false);
                player.addPotionEffect(slowFalling);
            }
        }.runTaskLater(Main.getInstance(), 20*20L);

        player.sendMessage(Main.getChatPrefix() + "§aGenieße deinen Rundflug!");
        player.sendMessage(Main.getChatPrefix() + "§fDu kannst nun für 20 Sekunden fliegen.");
        player.sendMessage(Main.getChatPrefix() + "§f§oNutzung auf eigene Gefahr ;)");
        player.playSound(player.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f,1f);
    }

}
