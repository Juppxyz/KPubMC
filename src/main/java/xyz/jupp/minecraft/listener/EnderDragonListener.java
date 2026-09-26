package xyz.jupp.minecraft.listener;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;

public class EnderDragonListener implements Listener {

    private static final double DRAGON_HEALTH = 2500.0;
    private static final int DRAGON_DAMAGE_MULTIPLIER = 6;

    @EventHandler
    public void onDragonSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            dragon.getAttribute(Attribute.MAX_HEALTH).setBaseValue(DRAGON_HEALTH);
            dragon.setHealth(DRAGON_HEALTH);
        }
    }

    @EventHandler
    public void onDragonAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof EnderDragon) {
            event.setDamage(event.getDamage() * DRAGON_DAMAGE_MULTIPLIER);
        }
    }
}
