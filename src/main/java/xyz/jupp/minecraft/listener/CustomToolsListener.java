package xyz.jupp.minecraft.listener;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
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
import xyz.jupp.minecraft.utils.Text;

public class CustomToolsListener implements Listener {

    // the listener is created in onEnable, so the plugin instance exists here
    private static final NamespacedKey FLAMETHROWER_KEY = new NamespacedKey(Main.getInstance(), "flamethrower_sword");
    private static final NamespacedKey POISON_BOW_KEY = new NamespacedKey(Main.getInstance(), "poison_bow");
    private static final String BEDROCK_BREAKER_NAME = new BedrockBreakerPickaxe().getItemName();
    private static final String POISON_BOW_PLAIN_NAME = Text.strip(new PoisonBow().getItemName());

    @EventHandler(ignoreCancelled = true)
    public void onFlamethrowerUse(PlayerArmSwingEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) return;

        // read-only view, no ItemMeta copy on every swing
        if (!item.getPersistentDataContainer().has(FLAMETHROWER_KEY, PersistentDataType.BYTE)) return;

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

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.WOODEN_PICKAXE) return;
        ItemMeta handMeta = hand.getItemMeta();
        if (handMeta == null || !BEDROCK_BREAKER_NAME.equals(Text.legacy(handMeta.customName()))) return;

        if (Locations.isLocationASpawn(clicked.getLocation())) return;

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
        if (meta == null || !meta.hasCustomName()) return;

        // PersistentDataContainer, sonst Fallback auf den Namen
        boolean isPoisonBow = meta.getPersistentDataContainer().has(POISON_BOW_KEY, PersistentDataType.BYTE)
                || Text.strip(Text.legacy(meta.customName())).equalsIgnoreCase(POISON_BOW_PLAIN_NAME);
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
