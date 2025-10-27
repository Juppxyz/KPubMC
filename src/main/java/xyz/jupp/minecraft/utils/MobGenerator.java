package xyz.jupp.minecraft.utils;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;


public class MobGenerator {

    private final static ArrayList<EntityType> ALLOWED_MOBS = new ArrayList<>(){{

    }};


    private Player player;

    public MobGenerator(Player player) {
        this.player = player;
    }

    private int isLevelled() {
        int randomInt = ThreadLocalRandom.current().nextInt(101);

        return 0;
    }

    public void spawn() {
        ThreadLocalRandom.current().nextInt(101);



    }



    private Entity getEntity() {
        return null;
    }
}
