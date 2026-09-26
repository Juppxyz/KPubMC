package xyz.jupp.minecraft.database;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.config.ConfigManager;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public final class MongoDB {

    public static final String CONNECTION_STRING_ENV = "KPUB_MONGO_URI";
    public static final String CONNECTION_STRING_CONFIG_KEY = "mongoConnectionString";

    // server error codes for "an index with this name/key already exists with other options"
    private static final int INDEX_OPTIONS_CONFLICT = 85;
    private static final int INDEX_KEY_SPECS_CONFLICT = 86;

    private final MongoClient mongoClient;
    private final MongoDatabase kpubMC;


    private MongoDB(String connectionString) {
        ConnectionString settings = new ConnectionString(connectionString);
        this.mongoClient = MongoClients.create(
                MongoClientSettings.builder().applyConnectionString(settings)
                        .applyToServerSettings(builder ->
                                builder.minHeartbeatFrequency(120, MILLISECONDS)
                                        .heartbeatFrequency(300, SECONDS))
                        // an unreachable database must not block a thread for the driver defaults (30 s / infinite),
                        // values given in the connection string win
                        .applyToClusterSettings(builder -> {
                            if (settings.getServerSelectionTimeout() == null) builder.serverSelectionTimeout(5, SECONDS);
                        })
                        .applyToSocketSettings(builder -> {
                            if (settings.getConnectTimeout() == null) builder.connectTimeout(5, SECONDS);
                            if (settings.getSocketTimeout() == null) builder.readTimeout(10, SECONDS);
                        })
                        .build()
        );
        this.kpubMC = mongoClient.getDatabase("kpubMC");
    }

    // single pattern, created explicitly in onEnable after the config was loaded
    private static volatile MongoDB instance = null;

    public static synchronized void connect() {
        if (instance != null) return;
        instance = new MongoDB(resolveConnectionString());
    }

    public static MongoDB getInstance() {
        MongoDB mongoDB = instance;
        if (mongoDB == null) throw new IllegalStateException("MongoDB is not connected");
        return mongoDB;
    }

    // Main Database
    public MongoDatabase getKpubMC() {
        return kpubMC;
    }

    public static synchronized void close() {
        if (instance == null) return;
        instance.mongoClient.close();
        instance = null;
    }


    private static String resolveConnectionString() {
        String fromEnvironment = System.getenv(CONNECTION_STRING_ENV);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment.trim();

        String fromConfig = ConfigManager.getManager().getMongoConnectionString();
        if (fromConfig != null && !fromConfig.isBlank()) return fromConfig.trim();

        throw new IllegalStateException("Keine MongoDB-Verbindung konfiguriert: Umgebungsvariable " + CONNECTION_STRING_ENV
                + " setzen oder \"" + CONNECTION_STRING_CONFIG_KEY + "\" in plugins/kpub/config.json eintragen."
                + " / No MongoDB connection configured: set the environment variable " + CONNECTION_STRING_ENV
                + " or the key \"" + CONNECTION_STRING_CONFIG_KEY + "\" in plugins/kpub/config.json.");
    }


    /**
     * Creates the (non-unique) lookup indexes if they are missing. Blocking, run it on a worker thread.
     */
    public void ensureIndexes() {
        createIndex("player", "uuid");
        createIndex("teams", "teamID");
        createIndex("chunks", "chunkID");
        createIndex("warps", "uuid");
    }

    private void createIndex(String collection, String field) {
        try {
            kpubMC.getCollection(collection).createIndex(Indexes.ascending(field));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == INDEX_OPTIONS_CONFLICT || e.getErrorCode() == INDEX_KEY_SPECS_CONFLICT) {
                Main.getInstance().getSLF4JLogger().info("Index {}.{} already exists with other options, keeping it", collection, field);
                return;
            }
            Main.getInstance().getSLF4JLogger().warn("Could not create index {}.{}: {}", collection, field, e.getMessage());
        } catch (MongoException e) {
            Main.getInstance().getSLF4JLogger().warn("Could not create index {}.{}: {}", collection, field, e.getMessage());
        }
    }

}
