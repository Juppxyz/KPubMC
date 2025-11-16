package xyz.jupp.minecraft.utils;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static xyz.jupp.minecraft.utils.Locations.isLocationASpawn;

public class MobEvent {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(MobEvent.class);

    public static void createMobEvent(@NotNull Player player) {
        Location bedLocation = player.getRespawnLocation();
        if (bedLocation == null || isLocationASpawn(bedLocation)) return;

        final World world = bedLocation.getWorld();
        final Random rnd = ThreadLocalRandom.current();
        final int ENTITY_COUNT = 14 + rnd.nextInt(16);
        final int MIN_DISTANCE = 32;
        final int MAX_DISTANCE = 64;

        for (int i = 0; i < ENTITY_COUNT; i++) {
            double distance = MIN_DISTANCE + rnd.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            double angle = rnd.nextDouble() * Math.PI * 2;
            double x = bedLocation.getX() + distance * Math.cos(angle);
            double z = bedLocation.getZ() + distance * Math.sin(angle);

            Chunk chunk = world.getChunkAt(new Location(world, x, 0, z));
            if (!chunk.isLoaded()) chunk.load();

            int y = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            y = Math.max(y, bedLocation.getBlockY() + 1);

            Location spawnLoc = new Location(world, x, y, z);

            Block feet = spawnLoc.getBlock();
            Block head = feet.getRelative(0, 1, 0);
            if (!feet.isPassable() || !head.isPassable()) {
                // minimal nach oben „suchen“
                boolean placed = false;
                for (int dy = 0; dy < 6; dy++) {
                    Block f = feet.getRelative(0, dy, 0);
                    Block h = f.getRelative(0, 1, 0);
                    if (f.isPassable() && h.isPassable()) {
                        spawnLoc.add(0, dy, 0);
                        placed = true;
                        break;
                    }
                }
                if (!placed) continue;
            }

            EntityType mobType = pickSafeMob(world); // siehe unten
            try {
                Entity entity = world.spawnEntity(
                        spawnLoc, mobType,
                        CreatureSpawnEvent.SpawnReason.CUSTOM
                );
                if (entity == null) {
                    log.warn("Spawn returned null for {}", mobType);
                    continue;
                }

                entity.setCustomName("§c§lMonster-Event");
                entity.setCustomNameVisible(true);

                // Killer Bunny nur setzen, wenn wirklich ein Rabbit
                if (mobType == EntityType.RABBIT && entity instanceof Rabbit rabbit) {
                    rabbit.setRabbitType(Rabbit.Type.THE_KILLER_BUNNY);
                }

                // Optional: VEX sofort auf Spieler hetzen, sonst despawnt er gerne
                if (entity instanceof Mob mob) {
                    mob.setTarget(player);
                }

            } catch (Exception ex) {
                log.error("Failed to spawn {} at {}: {}", mobType, spawnLoc, ex.toString());
            }
        }
    }

    private static final EntityType[] MOBS = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
            EntityType.WITCH, EntityType.RABBIT, EntityType.CAVE_SPIDER,
            EntityType.PILLAGER, EntityType.VEX, EntityType.BREEZE, EntityType.CREEPER
    };

    private static EntityType pickSafeMob(World world) {
        EntityType t = MOBS[ThreadLocalRandom.current().nextInt(MOBS.length)];

        // Tags: Phantoms nur nachts, um Sofort-Verbrennen zu vermeiden
        if (t == EntityType.PHANTOM && world.getTime() % 24000L < 12000L) {
            t = EntityType.ZOMBIE;
        }
        return t;
    }


}


