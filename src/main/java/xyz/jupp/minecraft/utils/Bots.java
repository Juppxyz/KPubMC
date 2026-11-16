package xyz.jupp.minecraft.utils;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Fake players of the PvpBot plugin (e.g. the sheriff's deputies, jail/Deputies). For the server they are real players:
 * they join, quit, die and fight. KPubMC must not treat them as people (no database row, no join message, no death
 * tax, no bounty head for their kills).
 */
public final class Bots {

    private Bots() {}

    // PvpBot tags every bot before it joins and keeps the tag (its "NPC" metadata is removed again after 5 s)
    private static final String TAG = "pvpbot";

    public static boolean isBot(@Nullable Entity entity) {
        return entity instanceof Player player && player.getScoreboardTags().contains(TAG);
    }
}
