package xyz.jupp.minecraft.database;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;
import static com.mongodb.client.model.Filters.eq;


public class PlayerCollection {

    private final MongoCollection<Document> playerCollection = MongoDB.getInstance().getKpubMC().getCollection("player");

    private Player player;
    private String uuid;

    public PlayerCollection(@NotNull Player player) {
        this.player = player;
        this.uuid = player.getUniqueId().toString();
    }


    /* create a new player in the database */
    public void createNewPlayerInDatabase() {
        if (existPlayerInDatabase()) return;
        Document playerDocument = new Document("uuid", getPlayer().getUniqueId().toString());
        playerDocument.append("teamInvites", false);
        playerDocument.append("cheatingKicks", 0);
        playerDocument.append("money", 250);
        playerDocument.append("teamID", null);
        playerDocument.append("loginStreak", 0);
        playerDocument.append("uuid", getPlayer().getUniqueId().toString());
        Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "create new player " + getPlayer().getUniqueId() + " in database.");
        playerCollection.insertOne(playerDocument);
    }


    public boolean existPlayerInDatabase() {
        Bson filter = eq("uuid", getPlayer().getUniqueId().toString());
        return (playerCollection.find(filter).first() != null);
    }


    public boolean changeTeamInvite() {
        Bson filter = eq("uuid", getUuid());
        Document document = playerCollection.find(filter).first();
        if (document == null) return false;

        boolean newValue = !document.getBoolean("teamInvites");
        Document updatedDocument = new Document("$set", new Document("teamInvites", newValue));
        playerCollection.updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "updated teamInvites from " + getPlayer().getUniqueId() + " to " + newValue);
        return newValue;
    }


    public boolean changeTeamID(String id) {
        Bson filter = eq("uuid", getUuid());
        Document document = playerCollection.find(filter).first();
        if (document == null) return false;
        ;
        Document updatedDocument = new Document("$set", new Document("teamID", id));
        playerCollection.updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "updated teamID from " + getPlayer().getUniqueId() + " to " + id);
        return true;
    }

    public int getLoginStreak() {
        Bson filter = eq("uuid", getUuid());
        Document document = playerCollection.find(filter).first();
        int loginStreak = document.getInteger("loginStreak");
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "get loginStreak from" + getPlayer().getUniqueId() + "(" + loginStreak + ")");
        return loginStreak;
    }

    public void incLoginStreak() {
        Bson filter = eq("uuid", getUuid());
        Document updatedDocument = new Document("$inc", new Document("loginStreak", 1));
        playerCollection.updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "increment loginStreak (" + getPlayer().getUniqueId() + ")");
    }

    public void resetLoginStreak() {
        Bson filter = eq("uuid", getUuid());
        Document updatedDocument = new Document("$set", new Document("loginStreak", 0));
        playerCollection.updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "reset loginStreak (" + getPlayer().getUniqueId() + ")");
    }

    public int getMoney() {
        Bson filter = eq("uuid", getUuid());
        Document document = playerCollection.find(filter).first();
        int money = document.getInteger("money");
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "get money from" + getPlayer().getUniqueId() + "(" + money + ")");
        return money;
    }


    public boolean updateMoney(int money) {
        Bson filter = eq("uuid", getUuid());
        Document updatedDocument = new Document("$set", new Document("money", money));
        playerCollection.updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "updated money from " + getPlayer().getUniqueId() + " to " + money);
        return false;
    }


    public int getCheatingKicks() {
        Bson filter = eq("uuid", getUuid());
        Document document = playerCollection.find(filter).first();
        if (document == null) return 0;

        int cheatingKicks = document.getInteger("cheatingKicks");
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "get cheatingKicks from" + getPlayer().getUniqueId() + "(" + cheatingKicks + ")");
        return cheatingKicks;
    }


    public boolean incrementCheatingKicks() {
        Bson filter = eq("uuid", getUuid());
        Document update = new Document("$inc", new Document("cheatingKicks", 1));
        playerCollection.updateOne(filter, update);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "updated cheatingKicks from " + getPlayer().getUniqueId());
        return false;
    }

    public Document getPlayerDocument() {
        Bson filter = eq("uuid", getPlayer().getUniqueId().toString());
        return playerCollection.find(filter).first();
    }

    // Getter
    private String getUuid() {
        return uuid;
    }
    public Player getPlayer() {
        return player;
    }


}
