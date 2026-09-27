package xyz.jupp.minecraft.commands;

import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.utils.Text;

// shared by the /create... commands, the ShopListener recognizes the NPCs by their custom name
final class NpcSpawner {

    private NpcSpawner() {}

    // spawns an invulnerable NPC without AI, gravity and collision at the player's position
    static <T extends LivingEntity> T spawn(@NotNull Player player, @NotNull EntityType type, @NotNull Class<T> entityClass,
                                            @NotNull String name, boolean nameVisible) {
        T npc = entityClass.cast(player.getWorld().spawnEntity(player.getLocation(), type));
        npc.customName(Text.entityName(name));
        npc.setCustomNameVisible(nameVisible);
        npc.setInvulnerable(true);
        npc.setAI(false);
        npc.setGravity(false);
        npc.setCollidable(false);
        return npc;
    }

    static void confirm(@NotNull Player player, @NotNull String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
        player.sendMessage(Main.getChatPrefix() + message);
    }

}
