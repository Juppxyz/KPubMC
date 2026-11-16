package xyz.jupp.minecraft.jail;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.listener.TeamAreaListener;
import xyz.jupp.minecraft.utils.Bots;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.Npcs;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The sheriff's deputies: in town the sheriff knows the faces on his board, in the wild he does not. A wanted player
 * whose warrant runs for HUNT_AFTER_DAYS and who comes to a spawn area (or appears there: login, respawn) is hunted by
 * PvpBot fake players that walk out of the sheriff's office. One deputy at first, two once SECOND_DEPUTY_AFTER_FAILURES
 * hunts of this warrant failed. They go after nobody else and nobody else can hurt them; they leave when the wanted
 * player is out of the spawn zone for OUTSIDE_GRACE_MS, after MAX_HUNT_MS, or when they changed world or logged out.
 * A catch jails the wanted player like a bounty hunter's (no escape), but without a head: the state pays nobody and
 * the money players put on the head goes back (DeathListener, Loans.warrantServed). From SIGHTING_AFTER_DAYS on, a wanted
 * player at the spawn is announced. Performance cut: no new hunt while the server ticks slower than MAX_AVG_TICK_MS on
 * average, and never more than MAX_DEPUTIES at once. Main thread only.
 */
public final class Deputies implements Listener {

    public static final int HUNT_AFTER_DAYS = 3;
    static final int SIGHTING_AFTER_DAYS = 2;
    // blocks around a spawn area that still count as the spawn: coming close is enough, leaving needs this much distance
    private static final int ZONE_MARGIN = 24;
    private static final long MAX_HUNT_MS = 5 * 60_000L;
    private static final long OUTSIDE_GRACE_MS = 30_000L;
    // after a hunt ended, before the next one for the same player: no spawning every second at the edge of the zone
    private static final long REARM_MS = 30_000L;
    private static final long SIGHTING_EVERY_MS = 10 * 60_000L;
    private static final int SECOND_DEPUTY_AFTER_FAILURES = 2;
    private static final int MAX_PER_HUNT = 2;
    private static final int MAX_DEPUTIES = 4;
    private static final double MAX_AVG_TICK_MS = 40.0;
    private static final double SHERIFF_RANGE = 160.0;
    static final String FACTION = "sheriff";
    static final String KIT = "sheriff";
    private static final String NAME = "Hilfssheriff";
    private static final int MAX_NAMES = 9;

    // what the faction always has, whatever an admin tuned: the deputies only go after their target, stay put in
    // between, keep nothing for a looter, do not wreck stashes or the spawn, and no 3.0 raycast that loads chunks
    private static final String[][] FACTION_RULES = {
            {"auto-target", "false"}, {"wander", "false"}, {"max-target-distance", "128"}, {"orbital", "false"},
            {"drop-items", "false"}, {"break-stashes", "false"}, {"cart-pvp", "false"}};

    private enum End { CAUGHT, FAILED, OVER }

    private static final class Hunt {
        final UUID target;
        final long wantedSince;
        final Locations.Area area;
        final List<String> deputies = new ArrayList<>();
        final long startedAt;
        long outsideSince;

        Hunt(UUID target, long wantedSince, Locations.Area area, long startedAt) {
            this.target = target;
            this.wantedSince = wantedSince;
            this.area = area;
            this.startedAt = startedAt;
        }
    }

    private record Failures(long wantedSince, int count) {}

    private static final Map<UUID, Hunt> hunts = new HashMap<>();
    // deputy name -> the player they hunt
    private static final Map<String, UUID> deputyOf = new HashMap<>();
    private static final Map<UUID, Failures> failures = new HashMap<>();
    private static final Map<UUID, Long> rearmAt = new HashMap<>();
    private static final Map<UUID, Long> lastSighting = new HashMap<>();
    private static boolean factionReady;
    private static boolean leftoversCleared;
    private static long busyLoggedAt;


    /** Every second; the first run waits until PvpBot has restored its saved bots. */
    public static void startTask() {
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), Deputies::tick, 100L, 20L);
    }

    /** onDisable: the deputies leave with KPubMC. */
    public static void shutdown() {
        for (Hunt hunt : new ArrayList<>(hunts.values())) {
            try {
                end(hunt, End.OVER);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Deputies of {} not removed: {}", hunt.target, e.toString());
            }
        }
    }

    /** One of the sheriff's deputies (a bot that hunts right now). */
    public static boolean isDeputy(@Nullable Entity entity) {
        return Bots.isBot(entity) && deputyOf.containsKey(entity.getName());
    }


    /* the hunt */

    private static void tick() {
        long now = System.currentTimeMillis();
        boolean pvpBot = PvpBotBridge.available();
        if (pvpBot && !leftoversCleared) {
            leftoversCleared = true;
            clearLeftovers();
        }
        for (Hunt hunt : new ArrayList<>(hunts.values())) update(hunt, now);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (Bots.isBot(player) || hunts.containsKey(player.getUniqueId())) continue;
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) continue;
            PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
            if (!pco.isWantedNow() || pco.getWantedSince() == 0) continue;
            long wantedFor = now - pco.getWantedSince();
            if (wantedFor < days(SIGHTING_AFTER_DAYS)) continue;
            Locations.Area zone = Locations.spawnAreaNear(player.getLocation(), ZONE_MARGIN);
            if (zone == null) continue;

            boolean deputiesOut = pvpBot && wantedFor >= days(HUNT_AFTER_DAYS) && now >= rearmAt.getOrDefault(player.getUniqueId(), 0L)
                    && start(player, pco, zone, now);
            // at most every SIGHTING_EVERY_MS, also when the deputies go out again and again (in and out by elytra)
            if (now - lastSighting.getOrDefault(player.getUniqueId(), 0L) >= SIGHTING_EVERY_MS) {
                lastSighting.put(player.getUniqueId(), now);
                Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§4§lGESICHTET §c" + player.getName() + " §fwurde am Spawn gesehen."
                        + (deputiesOut ? " §9Die Gehilfen des Sheriffs nehmen die Verfolgung auf." : "")));
            }
        }
    }

    private static long days(int days) {
        return days * 24L * 60L * 60L * 1000L;
    }

    // true if deputies went out
    private static boolean start(Player target, PlayerCacheObject pco, Locations.Area zone, long now) {
        UUID uuid = target.getUniqueId();
        // performance cut: a struggling server gets no fake players on top
        double tick = Bukkit.getServer().getAverageTickTime();
        int room = MAX_DEPUTIES - deputyOf.size();
        if (tick > MAX_AVG_TICK_MS || room <= 0) {
            rearmAt.put(uuid, now + REARM_MS);
            if (now - busyLoggedAt > 60_000L) {
                busyLoggedAt = now;
                Main.getInstance().getSLF4JLogger().info("No deputies for {}: {} ms per tick, {} deputies out", target.getName(),
                        String.format("%.1f", tick), deputyOf.size());
            }
            return false;
        }
        Location origin = origin(target.getLocation());
        if (origin == null) {
            rearmAt.put(uuid, now + REARM_MS);
            return false;
        }
        ensureFaction();

        Failures failed = failures.get(uuid);
        int fails = failed != null && failed.wantedSince() == pco.getWantedSince() ? failed.count() : 0;
        int count = Math.min(Math.min(fails >= SECOND_DEPUTY_AFTER_FAILURES ? 2 : 1, MAX_PER_HUNT), room);

        Hunt hunt = new Hunt(uuid, pco.getWantedSince(), zone, now);
        // the post covers the whole zone seen from the sheriff's office: PvpBot drops a target beyond radius + 8
        double radius = farthestCorner(zone, origin) + 1;
        Set<Block> used = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String name = freeName();
            if (name == null) break;
            Player deputy = PvpBotBridge.spawn(name, standNear(origin, used, target.getLocation()));
            if (deputy == null) continue;
            hunt.deputies.add(name);
            deputyOf.put(name, uuid);
            equip(deputy, name);
            PvpBotBridge.guard(name, origin, radius);
            PvpBotBridge.attack(name, target.getName());
        }
        if (hunt.deputies.isEmpty()) {
            rearmAt.put(uuid, now + REARM_MS);
            return false;
        }
        hunts.put(uuid, hunt);
        target.sendMessage(Main.getChatPrefix() + "§4Die Gehilfen des Sheriffs haben dich erkannt! §7Lauf, oder stell dich.");
        target.playSound(target.getLocation(), Sound.EVENT_RAID_HORN, 0.6f, 1.2f);
        Main.getInstance().getSLF4JLogger().info("{} deputies after {} (failed hunts of this warrant: {})", hunt.deputies.size(), target.getName(), fails);
        return true;
    }

    private static void update(Hunt hunt, long now) {
        Player target = Bukkit.getPlayer(hunt.target);
        // a logout the quit event did not see (should not happen): over, counts as failed
        if (target == null) {
            end(hunt, End.FAILED);
            return;
        }
        if (target.isDead()) return;
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(target);
        // paid, pardoned or a new warrant: nothing to hunt for any more
        if (!pco.isWantedNow() || pco.getWantedSince() != hunt.wantedSince) {
            end(hunt, End.OVER);
            return;
        }
        hunt.deputies.removeIf(name -> Bukkit.getPlayerExact(name) == null);
        if (hunt.deputies.isEmpty()) {
            end(hunt, End.FAILED);
            return;
        }
        if (now - hunt.startedAt >= MAX_HUNT_MS) {
            end(hunt, End.FAILED);
            return;
        }
        if (hunt.area.near(target.getLocation(), ZONE_MARGIN)) {
            hunt.outsideSince = 0;
            // back inside the post: PvpBot dropped the target when it left
            for (String name : hunt.deputies) PvpBotBridge.attack(name, target.getName());
        } else if (hunt.outsideSince == 0) {
            hunt.outsideSince = now;
        } else if (now - hunt.outsideSince >= OUTSIDE_GRACE_MS) {
            end(hunt, End.FAILED);
        }
    }

    private static void end(Hunt hunt, End how) {
        // removed first: the quit events of the deputies below find no hunt any more
        if (hunts.remove(hunt.target) != hunt) return;
        for (String name : hunt.deputies) {
            deputyOf.remove(name);
            PvpBotBridge.remove(name);
        }
        long now = System.currentTimeMillis();
        rearmAt.put(hunt.target, now + REARM_MS);
        Player target = Bukkit.getPlayer(hunt.target);
        switch (how) {
            case CAUGHT -> {
                failures.remove(hunt.target);
                Bukkit.broadcast(Text.section(Main.getChatPrefix() + "§9Die Gehilfen des Sheriffs haben §c"
                        + (target != null ? target.getName() : "einen Gesuchten") + " §9gefasst."));
            }
            case FAILED -> {
                Failures before = failures.get(hunt.target);
                int count = before != null && before.wantedSince() == hunt.wantedSince ? before.count() + 1 : 1;
                failures.put(hunt.target, new Failures(hunt.wantedSince, count));
                if (target != null && !target.isDead()) {
                    target.sendMessage(Main.getChatPrefix() + "§aDu hast die Gehilfen des Sheriffs abgehängt. §7Vorerst.");
                }
            }
            case OVER -> { }
        }
        Main.getInstance().getSLF4JLogger().info("hunt for {} ended: {}", target != null ? target.getName() : hunt.target, how);
    }


    /* where and who */

    // the sheriff closest to the wanted player, else the spawn point; null if neither is near
    private static @Nullable Location origin(Location at) {
        World world = at.getWorld();
        Location best = null;
        double bestDistance = SHERIFF_RANGE * SHERIFF_RANGE;
        String sheriff = Main.getSheriffVillagerName();
        for (Villager villager : world.getEntitiesByClass(Villager.class)) {
            if (!sheriff.equals(Npcs.visibleName(villager))) continue;
            double distance = villager.getLocation().distanceSquared(at);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = villager.getLocation();
            }
        }
        if (best != null) return best;
        Location spawn = Locations.getCurrentSpawn();
        return spawn.getWorld() == world && spawn.distanceSquared(at) <= SHERIFF_RANGE * SHERIFF_RANGE ? spawn : null;
    }

    // a free block next to the origin (feet and head free, solid below), facing the wanted player
    private static Location standNear(Location origin, Set<Block> used, Location lookAt) {
        Block center = origin.getBlock();
        for (int ring = 1; ring <= 3; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int dy = 0; dy >= -1; dy--) {
                        Block feet = center.getRelative(dx, dy, dz);
                        if (used.contains(feet) || !feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable()
                                || !feet.getRelative(0, -1, 0).getType().isSolid()) continue;
                        used.add(feet);
                        return facing(feet.getLocation().add(0.5, 0, 0.5), lookAt);
                    }
                }
            }
        }
        return facing(origin.clone(), lookAt);
    }

    private static Location facing(Location from, Location to) {
        from.setDirection(to.toVector().subtract(from.toVector()));
        from.setPitch(0);
        return from;
    }

    private static double farthestCorner(Locations.Area zone, Location from) {
        int[] a = zone.corner1(), b = zone.corner2();
        double best = 0;
        for (int x : new int[]{Math.min(a[0], b[0]) - ZONE_MARGIN, Math.max(a[0], b[0]) + 1 + ZONE_MARGIN}) {
            for (int z : new int[]{Math.min(a[2], b[2]) - ZONE_MARGIN, Math.max(a[2], b[2]) + 1 + ZONE_MARGIN}) {
                best = Math.max(best, Math.hypot(x - from.getX(), z - from.getZ()));
            }
        }
        return best;
    }

    private static @Nullable String freeName() {
        for (int i = 1; i <= MAX_NAMES; i++) {
            String name = NAME + i;
            if (!deputyOf.containsKey(name) && Bukkit.getPlayerExact(name) == null) return name;
        }
        return null;
    }

    // deputies of a crash or an old run (PvpBot does not save them, this is the safety net)
    private static void clearLeftovers() {
        for (int i = 1; i <= MAX_NAMES; i++) {
            String name = NAME + i;
            if (!deputyOf.containsKey(name) && PvpBotBridge.isBot(name)) PvpBotBridge.remove(name);
        }
    }


    /* the faction and the kit */

    // once per start: the faction exists and keeps its rules; the strength (skill, techniques) is the admins' to tune
    private static void ensureFaction() {
        if (factionReady) return;
        factionReady = true;
        if (!PvpBotBridge.factionExists(FACTION)) PvpBotBridge.command("faction create " + FACTION);
        for (String[] rule : FACTION_RULES) PvpBotBridge.command("faction set " + FACTION + " " + rule[0] + " " + rule[1]);
    }

    // the kit admins saved with /pvpbot kit save sheriff, else this one
    private static void equip(Player deputy, String name) {
        PvpBotBridge.command("faction join " + FACTION + " " + name);
        if (PvpBotBridge.kitExists(KIT)) {
            PvpBotBridge.command("kit give " + KIT + " " + name);
            return;
        }
        PlayerInventory inventory = deputy.getInventory();
        inventory.setHelmet(enchanted(Material.NETHERITE_HELMET, Enchantment.PROTECTION, 4));
        inventory.setChestplate(enchanted(Material.NETHERITE_CHESTPLATE, Enchantment.PROTECTION, 4));
        inventory.setLeggings(enchanted(Material.NETHERITE_LEGGINGS, Enchantment.PROTECTION, 4));
        ItemStack boots = enchanted(Material.NETHERITE_BOOTS, Enchantment.PROTECTION, 4);
        boots.addEnchantment(Enchantment.FEATHER_FALLING, 4);
        inventory.setBoots(boots);
        inventory.setItemInOffHand(new ItemStack(Material.SHIELD));
        inventory.addItem(
                enchanted(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5),
                enchanted(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 5),
                new ItemStack(Material.TOTEM_OF_UNDYING, 2),
                new ItemStack(Material.GOLDEN_APPLE, 16),
                new ItemStack(Material.ENDER_PEARL, 16),
                potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING, 6),
                potion(Material.POTION, PotionType.STRONG_STRENGTH, 2),
                potion(Material.POTION, PotionType.STRONG_SWIFTNESS, 2),
                new ItemStack(Material.WATER_BUCKET),
                new ItemStack(Material.COBBLESTONE, 64),
                new ItemStack(Material.GOLDEN_CARROT, 32));
    }

    private static ItemStack enchanted(Material material, Enchantment enchantment, int level) {
        ItemStack item = new ItemStack(material);
        item.addEnchantment(enchantment, level);
        item.addEnchantment(Enchantment.UNBREAKING, 3);
        return item;
    }

    private static ItemStack potion(Material material, PotionType type, int amount) {
        ItemStack item = new ItemStack(material, amount);
        item.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(type));
        return item;
    }


    /* events */

    // HIGH: before the other death handlers look at the drops; a deputy leaves nothing (PvpBot 2.0 has no drop-items)
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeputyDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        if (!isDeputy(dead)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        UUID hunted = deputyOf.get(dead.getName());
        if (hunted != null && Bots.isBot(dead)) {
            Hunt hunt = hunts.get(hunted);
            deputyOf.remove(dead.getName());
            if (hunt != null) hunt.deputies.remove(dead.getName());
            return;
        }
        Hunt hunt = hunts.get(dead.getUniqueId());
        if (hunt == null) return;
        Player killer = dead.getKiller();
        end(hunt, killer != null && hunt.deputies.contains(killer.getName()) && Bots.isBot(killer) ? End.CAUGHT : End.OVER);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID hunted = Bots.isBot(player) ? deputyOf.remove(player.getName()) : null;
        if (hunted != null) {
            Hunt hunt = hunts.get(hunted);
            if (hunt != null) hunt.deputies.remove(player.getName());
            return;
        }
        // logging out to get rid of them is a failed hunt (in combat it was a death and a catch already)
        Hunt hunt = hunts.get(player.getUniqueId());
        if (hunt != null) end(hunt, End.FAILED);
    }

    // at once, before the deputies' next tick: PvpBot would teleport a bot after a target in another world
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Hunt hunt = hunts.get(event.getPlayer().getUniqueId());
        if (hunt != null) end(hunt, End.FAILED);
    }

    // deputies hurt only their target and only their target can hurt them: no bystander dies at the spawn, nobody
    // farms them; mobs may still fight them
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Player attacker = TeamAreaListener.responsiblePlayer(event);
        Entity victim = event.getEntity();
        if (attacker != null && isDeputy(attacker)) {
            UUID target = deputyOf.get(attacker.getName());
            if (victim instanceof Player hit && !hit.getUniqueId().equals(target)) event.setCancelled(true);
            return;
        }
        if (isDeputy(victim) && attacker != null && !attacker.getUniqueId().equals(deputyOf.get(victim.getName()))) {
            event.setCancelled(true);
        }
    }

    // the wanted player's gear stays on the ground for whoever comes first, the deputies keep nothing
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (isDeputy(event.getEntity())) event.setCancelled(true);
    }

    static @NotNull String describe() {
        return hunts.size() + " hunts, " + deputyOf.size() + " deputies";
    }
}
