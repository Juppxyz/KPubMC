package xyz.jupp.minecraft.utils;

import org.bson.Document;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class MemberListDoc {

    public static Document getDoc(@NotNull Player player) {
        return getDoc(player, "member");
    }

    public static Document getDoc(@NotNull Player player, @NotNull String role) {
        return new Document("uuid", player.getUniqueId().toString())
                .append("role", role)
                .append("nickname", player.getName());
    }

}
