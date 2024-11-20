package xyz.jupp.minecraft.cache;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;

class PlayerCache {
    private static final HashMap<String, PlayerCacheObject> playerCacheMap = new HashMap<>();

    PlayerCacheObject getPlayer(@NotNull Player player) {
        if (!playerCacheMap.containsKey(player.getUniqueId().toString())){
            PlayerCacheObject newPlayerCacheObject = new PlayerCacheObject(player);
            playerCacheMap.put(player.getUniqueId().toString(), newPlayerCacheObject);
            return newPlayerCacheObject;
        }
        return playerCacheMap.get(player.getUniqueId().toString());
    }


    PlayerCacheObject reinitialisePlayerObject(@NotNull Player player) {
        if (playerCacheMap.containsKey(player.getUniqueId().toString())) {
            playerCacheMap.remove(player.getUniqueId().toString());
            PlayerCacheObject newPlayerCacheObject = new PlayerCacheObject(player);
            playerCacheMap.put(player.getUniqueId().toString(), newPlayerCacheObject);
            return newPlayerCacheObject;
        }
        return null;
    }


    void removePlayer(@NotNull Player player) {
        playerCacheMap.remove(player.getUniqueId().toString());
    }

}
