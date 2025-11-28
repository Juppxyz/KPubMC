package xyz.jupp.minecraft.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event.Result;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.items.BedrockBreakerPickaxe;

import xyz.jupp.minecraft.items.PoisonBow;
import xyz.jupp.minecraft.utils.Locations;

import java.util.Objects;

public class CustomToolsListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onFlamethrowerUse(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        NamespacedKey key = new NamespacedKey(Main.getInstance(), "flamethrower_sword");
        if (!meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location spawnLoc = eye.clone().add(dir.multiply(1.5));

        Fireball fb = world.spawn(spawnLoc, Fireball.class);
        fb.setShooter(player);
        fb.setIsIncendiary(true);
        fb.setYield(4.0f);
        fb.setVelocity(dir.multiply(2.8));

        player.getInventory().setItemInMainHand(null);
        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 1.2f);
        player.sendMessage(Main.getChatPrefix() + "§c§lFlammenball §fgezündet!");
    }



    @EventHandler(ignoreCancelled = true)
    public void onBedrockBreakerUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        if (Locations.isLocationASpawn(clicked.getLocation())) return;

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;
        ItemMeta handMeta = hand.getItemMeta();
        if (handMeta == null) return;

        ItemStack pickaxe = new BedrockBreakerPickaxe().getItemStack();
        ItemMeta pickMeta = pickaxe.getItemMeta();

        if (!hand.getType().equals(pickaxe.getType())) return;

        Component handName = handMeta.displayName();
        Component pickName = (pickMeta == null) ? null : pickMeta.displayName();

        if (!Objects.equals(handName, pickName)) return;

        if (clicked.getType() != Material.BEDROCK) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
            player.sendMessage(Main.getChatPrefix() + "§cDas Item funktioniert nur an Bedrock.");
            return;
        }

        event.setCancelled(true);

        World w = clicked.getWorld();
        w.spawnParticle(Particle.BLOCK_CRUMBLE, clicked.getLocation().add(0.5, 0.5, 0.5),
                60, 0.35, 0.35, 0.35, clicked.getBlockData());
        w.playSound(clicked.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.6f, 0.9f);
        w.playSound(clicked.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.25f, 0.7f);

        // Bedrock „zerstören“
        clicked.setType(Material.AIR, false);

        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand);
        }

        player.playSound(player.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.6f, 1.0f);
    }


    @EventHandler(ignoreCancelled = true)
    public void onShootWithBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack usedBow = event.getBow();
        if (usedBow == null || usedBow.getType() != Material.BOW) return;

        ItemMeta meta = usedBow.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        // Prüfe über Namen ODER PersistentDataContainer
        PoisonBow poisonBow = new PoisonBow();

        boolean isPoisonBow = false;

        // Bessere Methode: PersistentDataContainer prüfen
        if (meta.getPersistentDataContainer()
                .has(new NamespacedKey(Main.getInstance(), "poison_bow"), PersistentDataType.BYTE)) {
            isPoisonBow = true;
        }
        // oder Fallback auf Name:
        else if (ChatColor.stripColor(meta.getDisplayName())
                .equalsIgnoreCase(ChatColor.stripColor(poisonBow.getItemName()))) {
            isPoisonBow = true;
        }

        if (!isPoisonBow) return;

        Entity proj = event.getProjectile();
        Arrow arrow;

        if (proj instanceof Arrow a) {
            arrow = a;
        } else {
            // Falls kein Arrow, ersetze Projektil
            arrow = player.getWorld().spawn(proj.getLocation(), Arrow.class);
            arrow.setShooter(player);
            arrow.setVelocity(proj.getVelocity());
            if (proj instanceof AbstractArrow aa && aa.isCritical()) {
                arrow.setCritical(true);
            }
            proj.remove();
            event.setProjectile(arrow);
        }

        // Gift-Effekt hinzufügen (8 Sekunden)
        arrow.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 20 * 8, 0), true);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
        arrow.setColor(Color.LIME);

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.7f, 1.4f);
    }

}