package xyz.jupp.minecraft.utils;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.WanderingTrader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;

// the NPCs from the /create... commands, recognized by their custom name
public final class Npcs {

    private Npcs() {}

    public static boolean isNpc(@NotNull Entity entity) {
        EntityType type = entity.getType();
        if (type == EntityType.VILLAGER) {
            String name = visibleName(entity);
            return Main.getShopVillagerName().equals(name)
                    || Main.getFinanceVillagerFredName().equals(name)
                    || Main.getJewelerVillagerName().equals(name);
        }
        // Morpheus has a hidden name
        if (type == EntityType.VINDICATOR) {
            return Main.getBlackMarketDealerVillagerName().equals(Text.legacyOrNull(entity.customName()));
        }
        if (type == EntityType.WANDERING_TRADER) {
            return Main.getTeamPointsDealerVillagerName().equals(visibleName(entity));
        }
        return false;
    }

    // the custom name as legacy text, null if there is none or it is not visible
    public static @Nullable String visibleName(@NotNull Entity entity) {
        return entity.isCustomNameVisible() ? Text.legacyOrNull(entity.customName()) : null;
    }

    // A name set by the API does not protect against despawning like a name tag does, without this
    // Morpheus vanished when no player was near. Peaceful still removes him: the server removes every
    // monster type there, persistent or not (the API override for that does nothing in Paper 26.3).
    public static void keep(@NotNull LivingEntity npc) {
        npc.setRemoveWhenFarAway(false);
        npc.setPersistent(true);
        if (npc instanceof WanderingTrader trader) trader.setDespawnDelay(0);
    }

    // NPCs from before the fix, for the entities that are already loaded at startup
    public static void keepLoaded() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (isNpc(entity)) keep(entity);
            }
        }
    }

}
