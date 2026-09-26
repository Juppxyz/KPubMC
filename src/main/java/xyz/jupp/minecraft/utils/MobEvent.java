package xyz.jupp.minecraft.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static xyz.jupp.minecraft.utils.Locations.isLocationASpawn;

// main thread only (spawns entities and loads chunks)
public class MobEvent {

    private static final Component EVENT_MOB_NAME = Text.entityName("§c§lMonster-Event");

    private static final EntityType[] MOBS = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
            EntityType.WITCH, EntityType.RABBIT, EntityType.CAVE_SPIDER,
            EntityType.PILLAGER, EntityType.VEX, EntityType.BREEZE, EntityType.CREEPER
    };

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
            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);

            Chunk chunk = world.getChunkAt(blockX >> 4, blockZ >> 4);
            if (!chunk.isLoaded()) chunk.load();

            int y = world.getHighestBlockYAt(blockX, blockZ) + 1;
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

            EntityType mobType = MOBS[rnd.nextInt(MOBS.length)];
            try {
                Entity entity = world.spawnEntity(spawnLoc, mobType, CreatureSpawnEvent.SpawnReason.CUSTOM);

                entity.customName(EVENT_MOB_NAME);
                entity.setCustomNameVisible(true);

                // Killer Bunny nur setzen, wenn wirklich ein Rabbit
                if (entity instanceof Rabbit rabbit) {
                    rabbit.setRabbitType(Rabbit.Type.THE_KILLER_BUNNY);
                }

                // VEX sofort auf Spieler hetzen, sonst despawnt er gerne
                if (entity instanceof Mob mob) {
                    mob.setTarget(player);
                }

            } catch (RuntimeException ex) {
                Main.getInstance().getSLF4JLogger().error("Failed to spawn {} at {}: {}", mobType, spawnLoc, ex.toString());
            }
        }
    }

}
