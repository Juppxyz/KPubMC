package xyz.jupp.minecraft.database;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Secrets;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class MongoDB {

    /* knowledge variables */

    private MongoClient mongoClient;
    private MongoDatabase kpubMC;
    private ExecutorService executor;


    private MongoDB() {
        this.mongoClient = MongoClients.create(
                MongoClientSettings.builder().applyConnectionString(new ConnectionString(Secrets.mongoDBConnectionString))
                        .applyToServerSettings(builder ->
                                builder.minHeartbeatFrequency(120, MILLISECONDS)
                                        .heartbeatFrequency(300, SECONDS)).build()
        );

        this.kpubMC = mongoClient.getDatabase("kpubMC");
        this.executor = Executors.newFixedThreadPool(4);
        Logger.console("connected successfully to database");
    }

    // single pattern
    private static MongoDB instance = null;
    public static MongoDB getInstance() {
        return instance == null ? instance = new MongoDB() : instance;
    }

    // Main Database
    public MongoDatabase getKpubMC() {
        return kpubMC;
    }


    public CompletableFuture<Void> insertDocumentAsync(String collectionName, Document document) {
        return CompletableFuture.runAsync(() -> {
            try {
                kpubMC.getCollection(collectionName).insertOne(document);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, executor);
    }


    public CompletableFuture<Document> findDocumentAsync(String collectionName, String key, String value) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return kpubMC.getCollection(collectionName).find(eq(key, value)).first();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }, executor);
    }


    // Ressource sauber schließen, wenn Minecraft beendet wird
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
        }
        mongoClient.close();
    }


}
