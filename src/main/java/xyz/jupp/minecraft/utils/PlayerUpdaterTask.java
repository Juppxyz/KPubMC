package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.PlayerCacheObject;

import java.util.Random;


public class PlayerUpdaterTask implements TaskHandler.Tasks {

    public static EntityType randomMob() {
        EntityType[] mobs = {
                EntityType.ZOMBIE,
                EntityType.SKELETON,
                EntityType.SPIDER,
                EntityType.WITCH,
                EntityType.RABBIT,
                EntityType.CAVE_SPIDER,
                EntityType.PHANTOM,
                EntityType.PILLAGER,
                EntityType.VEX,
                EntityType.BREEZE,

        };

        Random random = new Random();
        return mobs[random.nextInt(mobs.length)];
    }


    public void spawnMobEvent(Location bedLocation) {
        Random random = new Random();
        final int ENTITY_COUNT = 10 + random.nextInt(16);
        final int MIN_DISTANCE = 32;
        final int MAX_DISTANCE = 160;

        World world = bedLocation.getWorld();
        for (int i = 0; i < ENTITY_COUNT; i++) {
            double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            double angle = random.nextDouble() * 2 * Math.PI;
            double x = bedLocation.getX() + distance * Math.cos(angle);
            double z = bedLocation.getZ() + distance * Math.sin(angle);
            int y = world.getHighestBlockYAt((int) x, (int) z) + 1;

            Location spawnLoc = new Location(world, x, y, z);

            EntityType mobType = randomMob();
            Entity entity = world.spawnEntity(spawnLoc, mobType); // Nur EIN spawn

            if (mobType == EntityType.RABBIT && entity instanceof org.bukkit.entity.Rabbit) {
                org.bukkit.entity.Rabbit rabbit = (org.bukkit.entity.Rabbit) entity;
                rabbit.setRabbitType(org.bukkit.entity.Rabbit.Type.THE_KILLER_BUNNY);
            }
        }
    }


    @Override
    public boolean startTask() {
        Bukkit.getScheduler().scheduleAsyncRepeatingTask(Main.getInstance(), () -> {
            Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "§fupdating players data..");

            Random random = new Random();
            boolean isMonsterEvent = random.nextInt(1000) == 0;

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
                    playerCacheObject.updatePlayer();

                    Location bedSpawn = player.getBedSpawnLocation();
                    if (bedSpawn != null && bedSpawn.getWorld().equals(player.getWorld())) {
                        double distance = bedSpawn.distance(player.getLocation());
                        if ( distance <= 160 && isMonsterEvent) {
                            player.sendMessage(Main.getChatPrefix() + "§cSicherheitsmeldung: Ungeziefer im Schlafbereich erkannt.");
                            Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "created monster event for player " + player.getName() + " at " + bedSpawn.toString());
                            spawnMobEvent(bedSpawn);
                        }

                    }

                }
            });

            Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "§ffinished updating players data");
        }, 0, 20L * 900);
        return false;
    }

}
