package org.apache.solr.handler.dataimport;

// MongoDB imports
import com.mongodb.client.*;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;

import org.bson.Document;

// Logging imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Java util imports
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;

// Solr imports
import static org.apache.solr.handler.dataimport.DataImportHandlerException.SEVERE;
import static org.apache.solr.handler.dataimport.DataImportHandlerException.wrapAndThrow;

public class MongoDataSource extends DataSource<Iterator<Map<String, Object>>> {
    private static final Logger LOG = LoggerFactory.getLogger(MongoDataSource.class);
 
    private MongoClient mongoClient;
    private MongoDatabase mongoDb;
    private MongoCollection<Document> mongoCollection;
    private MongoCursor<Document> mongoCursor;

    @Override
    public void init(Context context, Properties initProps) {
        String databaseName = initProps.getProperty(DATABASE);
        String host = initProps.getProperty(HOST, "localhost");
        String port = initProps.getProperty(PORT, "27017");
        String username = initProps.getProperty(USERNAME);
        String password = initProps.getProperty(PASSWORD);
        String authSource = initProps.getProperty(AUTH_SOURCE, "admin"); 

        if (databaseName == null) {
            throw new DataImportHandlerException(SEVERE, "Database must be supplied");
        }

        try {
            MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder();
            
            if (username != null && password != null) {
                MongoCredential credential = MongoCredential.createCredential(
                    username,
                    authSource,
                    password.toCharArray()
                );
                
                settingsBuilder.credential(credential);
            }
            
            // 建立連接字串
            ConnectionString connectionString = new ConnectionString(
                String.format("mongodb://%s:%s", host, port)
            );
            
            settingsBuilder.applyConnectionString(connectionString);
            
            MongoClientSettings settings = settingsBuilder.build();
            this.mongoClient = MongoClients.create(settings);
            this.mongoDb = mongoClient.getDatabase(databaseName);
            
            // 測試連接
            try {
                mongoDb.runCommand(new Document("ping", 1));
                LOG.info("Successfully connected to MongoDB");
            } catch (Exception e) {
                throw new DataImportHandlerException(SEVERE, 
                    "Failed to connect to MongoDB: " + e.getMessage(), e);
            }

        } catch (Exception e) {
            throw new DataImportHandlerException(SEVERE, 
                "Unable to connect to MongoDB: " + e.getMessage(), e);
        }
    }

    public Iterator<Map<String, Object>> getData(String query, String collection) {
        this.mongoCollection = this.mongoDb.getCollection(collection);
        return getData(query);
    }

    @Override
    public Iterator<Map<String, Object>> getData(String query) {
        try {
            Document queryObject = Document.parse(query);
            LOG.debug("Executing MongoQuery: " + query);

            long start = System.currentTimeMillis();
            mongoCursor = this.mongoCollection.find(queryObject).iterator();
            LOG.trace("Time taken for mongo: " + (System.currentTimeMillis() - start));

            return new ResultSetIterator(mongoCursor).getIterator();
        } catch (Exception e) {
            throw new DataImportHandlerException(SEVERE, 
                "Error executing query: " + e.getMessage(), e);
        }
    }

    private class ResultSetIterator {
        private final MongoCursor<Document> mongoCursor;
        private final Iterator<Map<String, Object>> rSetIterator;

        public ResultSetIterator(MongoCursor<Document> mongoCursor) {
            this.mongoCursor = mongoCursor;
            this.rSetIterator = new Iterator<Map<String, Object>>() {
                public boolean hasNext() { return hasnext(); }
                public Map<String, Object> next() { return getARow(); }
                public void remove() { /* do nothing */ }
            };
        }

        public Iterator<Map<String, Object>> getIterator() {
            return rSetIterator;
        }

        private Map<String, Object> getARow() {
            Document doc = mongoCursor.next();
            Map<String, Object> result = new HashMap<>();
            for (String key : doc.keySet()) {
                result.put(key, doc.get(key));
            }
            return result;
        }

        private boolean hasnext() {
            if (mongoCursor == null) return false;
            try {
                if (mongoCursor.hasNext()) {
                    return true;
                } else {
                    close();
                    return false;
                }
            } catch (Exception e) {
                close();
                wrapAndThrow(SEVERE, e);
                return false;
            }
        }

        private void close() {
            try {
                if (mongoCursor != null) mongoCursor.close();
            } catch (Exception e) {
                LOG.warn("Exception while closing cursor", e);
            }
        }
    }

    @Override
    public void close() {
        if (this.mongoCursor != null) {
            this.mongoCursor.close();
        }
        if (this.mongoClient != null) {
            this.mongoClient.close();
        }
    }

    public static final String DATABASE = "database";
    public static final String HOST = "host";
    public static final String PORT = "port";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String AUTH_SOURCE = "authSource";
}