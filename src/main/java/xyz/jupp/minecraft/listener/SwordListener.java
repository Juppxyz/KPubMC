package xyz.jupp.minecraft.listener;

import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import xyz.jupp.minecraft.items.LightningSword;

import java.util.HashMap;
import java.util.UUID;

@Deprecated
public class SwordListener implements Listener {

    private final HashMap<UUID, Long> cooldowns;

    public SwordListener() {
        this.cooldowns = new HashMap<>();
    }

    @EventHandler
    public void onEntityHit(EntityDamageByEntityEvent event) {
        // Filter quickly: Only handle when the damager is a player
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getDamager();

        // ensure the player is holding the correct item (sturmschwert)
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (!isSwordOfLightning(heldItem)) {
            return;
        }

        // cooldown check
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        if (cooldowns.containsKey(playerId)) {
            long lastUse = cooldowns.get(playerId);
            if (currentTime - lastUse < 5000) { // 5-second cooldown
                player.sendMessage(ChatColor.RED + "Das STURMSCHWERT muss aufladen!");
                return;
            }
        }

        // strike lightning at the target entity's location
        Entity target = event.getEntity();
        if (target instanceof LivingEntity) {
            target.getWorld().strikeLightning(target.getLocation());
            // add player to cooldown
            cooldowns.put(playerId, currentTime);
        }
    }

    private boolean isSwordOfLightning(ItemStack item) {
        if (item == null || item.getItemMeta() == null) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();

        if (data.has(LightningSword.getInstance().getSwordKey(), PersistentDataType.BYTE)) {
            Byte value = data.get(LightningSword.getInstance().getSwordKey(), PersistentDataType.BYTE);
            return value != null && value == 1;
        }

        return false;
    }
}
