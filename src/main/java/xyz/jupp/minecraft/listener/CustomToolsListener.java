package xyz.jupp.minecraft.listener;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Bukkit;
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
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.items.BedrockBreakerPickaxe;
import xyz.jupp.minecraft.items.Charges;
import xyz.jupp.minecraft.items.ForgedPapers;
import xyz.jupp.minecraft.items.GrapplingHook;
import xyz.jupp.minecraft.items.TrackerCompass;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.PlayerTracker;
import xyz.jupp.minecraft.utils.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CustomToolsListener implements Listener {

    // the listener is created in onEnable, so the plugin instance exists here
    private static final NamespacedKey FLAMETHROWER_KEY = new NamespacedKey(Main.getInstance(), "flamethrower_sword");
    private static final NamespacedKey POISON_BOW_KEY = new NamespacedKey(Main.getInstance(), "poison_bow");
    private static final String BEDROCK_BREAKER_NAME = new BedrockBreakerPickaxe().getItemName();

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

        // nur echte Giftbögen tragen den PDC-Marker, ein im Amboss umbenannter Bogen nicht
        if (!meta.getPersistentDataContainer().has(POISON_BOW_KEY, PersistentDataType.BYTE)) return;

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

    /* black market tools with charges: tracker compass, forged papers, grappling hook */

    private static final long HOOK_FALL_PROTECTION_MILLIS = 3000;
    // player -> end of the fall protection after a grappling hook pull (main thread)
    private static final Map<UUID, Long> hookFallProtection = new HashMap<>();

    // right clicks into the air arrive cancelled, so no ignoreCancelled here; another plugin can still deny the item
    private static @Nullable ItemStack rightClickTool(PlayerInteractEvent event, Material type, NamespacedKey key) {
        if (event.getHand() != EquipmentSlot.HAND || event.useItemInHand() == Event.Result.DENY) return null;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return null;
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (hand.getType() != type || !hand.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return null;
        return hand;
    }

    @EventHandler
    public void onTrackerCompassUse(PlayerInteractEvent event) {
        ItemStack compass = rightClickTool(event, Material.COMPASS, TrackerCompass.KEY);
        if (compass == null) return;
        // also keeps a lodestone from turning it into a lodestone compass
        event.setCancelled(true);
        Player player = event.getPlayer();

        long left = PlayerTracker.secondsLeft(player);
        if (left > 0) {
            player.sendActionBar(Text.section("§7Der Spürkompass sucht noch §f" + left / 60 + ":" + String.format("%02d", left % 60)));
            return;
        }
        int charges = Charges.use(compass);
        if (charges < 0) return;
        PlayerTracker.start(player, TrackerCompass.MINUTES * 60_000L);
        player.getInventory().setItemInMainHand(charges == 0 ? null : compass);
        player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.6f, 1.6f);
        player.sendMessage(Main.getChatPrefix() + "§bDer Spürkompass erwacht §7(" + TrackerCompass.MINUTES + " Minuten).");
        if (charges == 0) player.sendMessage(Main.getChatPrefix() + "§7Das war seine letzte Ladung, er zerfällt in deiner Hand.");
    }

    @EventHandler
    public void onForgedPapersUse(PlayerInteractEvent event) {
        ItemStack papers = rightClickTool(event, Material.PAPER, ForgedPapers.KEY);
        if (papers == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();

        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        if (pco.isJail()) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 2f);
            player.sendMessage(Main.getChatPrefix() + "§cIm Gefängnis helfen dir auch keine Papiere.");
            return;
        }
        if (!pco.isWanted()) {
            player.sendMessage(Main.getChatPrefix() + "§7Du wirst gar nicht gesucht. Heb sie dir gut auf.");
            return;
        }
        papers.setAmount(papers.getAmount() - 1);
        player.getInventory().setItemInMainHand(papers.getAmount() <= 0 ? null : papers);
        JailHandler.setPlayerWanted(player, false);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
        player.sendMessage(Main.getChatPrefix() + "§fNeue Identität, neues Glück. §aDie Fahndung nach dir ist eingestellt.");
        Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§fDie Fahndung nach §a" + player.getName() + " §fwurde eingestellt."));
        Logger.console("forged papers: " + player.getUniqueId() + " is no longer wanted");
    }

    @EventHandler
    public void onGrapplingHook(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.IN_GROUND || event.getHand() == null) return;
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        ItemStack rod = player.getInventory().getItem(hand);
        if (rod.getType() != Material.FISHING_ROD || !rod.getPersistentDataContainer().has(GrapplingHook.KEY, PersistentDataType.BYTE)) return;
        if (JailHandler.isPlayerInJail(player)) {
            player.sendMessage(Main.getChatPrefix() + "§cIm Gefängnis greift der Haken nicht.");
            return;
        }

        Location hook = event.getHook().getLocation();
        Location from = player.getLocation();
        if (hook.getWorld() != from.getWorld()) return;
        Vector delta = hook.toVector().subtract(from.toVector());
        double distance = delta.length();
        if (distance < 2) return;

        // fast enough for about the whole distance, capped so nobody is shot across the map
        Vector horizontal = delta.clone().setY(0);
        double speed = Math.min(0.25 + distance * 0.09, 2.0);
        Vector velocity = horizontal.lengthSquared() < 1.0e-6 ? new Vector() : horizontal.normalize().multiply(speed);
        velocity.setY(Math.max(-0.4, Math.min(1.4, 0.42 + delta.getY() * 0.1)));
        player.setVelocity(velocity);
        player.setFallDistance(0);
        hookFallProtection.put(player.getUniqueId(), System.currentTimeMillis() + HOOK_FALL_PROTECTION_MILLIS);
        player.setCooldown(Material.FISHING_ROD, 20);
        player.getWorld().playSound(from, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.7f);

        int charges = Charges.use(rod);
        if (charges == 0) {
            player.getInventory().setItem(hand, null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            player.sendMessage(Main.getChatPrefix() + "§7Der Enterhaken ist verschlissen.");
        } else if (charges > 0) {
            player.getInventory().setItem(hand, rod);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallAfterHook(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) return;
        Long until = hookFallProtection.remove(player.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) event.setCancelled(true);
    }

}
