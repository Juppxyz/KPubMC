package xyz.jupp.minecraft.utils;

import org.bson.Document;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class MemberListDoc {

    public static Document getDoc(@NotNull Player player) {
        Document document = new Document("uuid", player);
        document.put("uuid", player.getUniqueId().toString());
        document.put("role", "member");
        document.put("nickname", player.getName());
        return document;
    }

    public static Document getDoc(@NotNull Player player, @NotNull String role) {
        Document document = new Document("uuid", player);
        document.put("uuid", player.getUniqueId().toString());
        document.put("role", role);
        document.put("nickname", player.getName());
        return document;
    }

}
