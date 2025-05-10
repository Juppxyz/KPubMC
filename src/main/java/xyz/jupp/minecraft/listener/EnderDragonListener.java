package xyz.jupp.minecraft.listener;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;

public class EnderDragonListener implements Listener {

    @EventHandler
    public void onDragonSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof EnderDragon) {
            EnderDragon dragon = (EnderDragon) event.getEntity();

            // Maximalleben setzen
            dragon.getAttribute(Attribute.MAX_HEALTH).setBaseValue(1000.0);
            dragon.setHealth(1000.0);

            // Name setzen
            dragon.setCustomName("§c§lIgnaroth");
            dragon.setCustomNameVisible(true);
        }
    }

    @EventHandler
    public void onDragonAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof EnderDragon) {
            // Schaden des Drachen verdreifachen
            event.setDamage(event.getDamage() * 4);
        }
    }
}
