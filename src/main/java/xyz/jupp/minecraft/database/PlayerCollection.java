package xyz.jupp.minecraft.database;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.Main;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;


public class PlayerCollection {

    private final MongoCollection<Document> playerCollection = MongoDB.getInstance().getKpubMC().getCollection("player");

    private Player player;
    private String uuid;

    public PlayerCollection(@NotNull Player player) {
        this.player = player;
        this.uuid = player.getUniqueId().toString();
    }

    // only usage fpr the prelogin
    public PlayerCollection(@NotNull String uuid) {
        this.uuid = uuid;
    }


    /* create a new player in the database */
    public void createNewPlayerInDatabase() {
        // currently not needed, this checks is in front of the currently only usage
        //if (existPlayerInDatabase()) return;

        String uuid;
        if (getPlayer() == null) {
            uuid = getUuid();
        }else {
            uuid = getPlayer().getUniqueId().toString();
        }

        Document playerDocument = new Document("uuid", uuid);
        playerDocument.append("teamInvites", false);
        playerDocument.append("cheatingKicks", 0);
        playerDocument.append("loginStreak", 0);
        playerDocument.append("jail", false);
        playerDocument.append("jailEnd", 0L);
        playerDocument.append("isWanted", false);
        playerDocument.append("teamID", null);
        playerDocument.append("money", 250);
        playerDocument.append("uuid", uuid);
        Bukkit.getConsoleSender().sendMessage(Main.getConsolePrefix() + "create new player " + uuid + " in database.");
        playerCollection.insertOne(playerDocument);
    }

    public boolean existPlayerInDatabase() {
        Bson filter;
        if (getPlayer() == null){
            filter = eq("uuid", getUuid());
            return (playerCollection.find(filter).first() != null);
        }
        filter = eq("uuid", getPlayer().getUniqueId().toString());
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


    public int getMoney() {
        Bson filter = eq("uuid", getUuid());
        Document document = playerCollection.find(filter).first();
        int money = document.getInteger("money");
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "get money from " + getPlayer().getUniqueId() + "(" + money + ")");
        return money;
    }

    public boolean updateMoney(int money) {
        Bson filter = eq("uuid", getUuid());
        Document updatedDocument = new Document("$set", new Document("money", money));
        playerCollection.updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "updated money from " + getPlayer().getUniqueId() + " to " + money);
        return false;
    }


    public boolean setJail(boolean jail, long jailEnd) {
        Bson filter = eq("uuid", getUuid());
        Document updatedDocument = new Document("$set", new Document("jail", jail).append("jailEnd", jailEnd));
        playerCollection.updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "set jail for " + getPlayer().getUniqueId() + " until " + jailEnd);
        return false;
    }

    public boolean unsetJail(long jailEnd) {
        Bson filter = eq("uuid", getUuid());
        Document updatedDocument = new Document("$set", new Document("jail", false).append("jailEnd", jailEnd));
        playerCollection.updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "unset jail for " + getPlayer().getUniqueId());
        return false;
    }

    public boolean setIsWanted(boolean isWanted) {
        Bson filter = eq("uuid", getUuid());
        Document updatedDocument = new Document("$set", new Document("isWanted", isWanted));
        playerCollection.updateOne(filter, updatedDocument);
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "set wanted for " + getPlayer().getUniqueId() + " to " + isWanted);
        return false;
    }


    public List<Document> getWantedPlayers() {
        long now = System.currentTimeMillis();

        Bson filter = new Document("isWanted", true)
                .append("jailEnd", new Document("$gt", now));

        List<Document> membersList = playerCollection
                .find(filter)
                .sort(new Document("jailEnd", -1))
                .into(new ArrayList<>());

        return membersList;
    }



    // Getter
    private String getUuid() {
        return uuid;
    }
    public Player getPlayer() {
        return player;
    }
    public Document getPlayerDocument() {
        Bson filter = eq("uuid", getPlayer().getUniqueId().toString());
        return playerCollection.find(filter).first();
    }





    // currently not in usage
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


    public int getLoginStreak() {
        Bson filter = eq("uuid", getUuid());
        Document document = playerCollection.find(filter).first();
        int loginStreak = document.getInteger("loginStreak");
        Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "get loginStreak from " + getPlayer().getUniqueId() + "(" + loginStreak + ")");
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

}
