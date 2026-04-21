package com.protogemcouch.couchbase;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.protogemcouch.config.ServerConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CouchbaseRepository {

    private final ServerConfig config;

    private Cluster cluster;
    private Bucket bucket;
    private Scope scope;
    private Collection collection;

    public CouchbaseRepository(ServerConfig config) {
        this.config = config;
    }

    public void connect() {
        System.out.println("Connecting to Couchbase...");
        System.out.println("  connstr    = " + config.getCouchbaseConnectionString());
        System.out.println("  bucket     = " + config.getCouchbaseBucket());
        System.out.println("  scope      = " + config.getCouchbaseScope());
        System.out.println("  collection = " + config.getCouchbaseCollection());

        ClusterEnvironment env = ClusterEnvironment.builder()
                .timeoutConfig(tc -> tc
                        .connectTimeout(Duration.ofSeconds(10))
                        .kvTimeout(Duration.ofSeconds(5)))
                .build();

        cluster = Cluster.connect(
                config.getCouchbaseConnectionString(),
                com.couchbase.client.java.ClusterOptions.clusterOptions(
                        config.getCouchbaseUsername(),
                        config.getCouchbasePassword()
                ).environment(env)
        );

        bucket = cluster.bucket(config.getCouchbaseBucket());
        bucket.waitUntilReady(Duration.ofSeconds(15));
        scope = bucket.scope(config.getCouchbaseScope());
        collection = scope.collection(config.getCouchbaseCollection());

        System.out.println("Couchbase connected.");
    }

    public void disconnect() {
        try {
            if (cluster != null) {
                cluster.disconnect();
            }
        } catch (Exception ignored) {
        }
    }

    public String get(String docId) {
        try {
            GetResult result = collection.get(docId);
            JsonObject content = result.contentAsObject();
            System.out.println("CB GET OK docId=" + docId + " content=" + content);
            if (content.containsKey("value")) {
                return content.getString("value");
            }
            return null;
        } catch (Exception e) {
            System.out.println("CB GET MISS/ERROR docId=" + docId + " msg=" + e.getMessage());
            return null;
        }
    }

    public Map<String, String> getAll(String region, List<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            String docId = docId(region, key);
            String value = get(docId);
            out.put(key, value);
        }
        return out;
    }

    public void put(String docId, String value) {
        JsonObject body = JsonObject.create().put("value", value);
        collection.upsert(docId, body);
        System.out.println("CB UPSERT OK docId=" + docId + " body=" + body);
    }

    public void remove(String docId) {
        try {
            collection.remove(docId);
            System.out.println("CB REMOVE OK docId=" + docId);
        } catch (Exception e) {
            System.out.println("CB REMOVE MISS/ERROR docId=" + docId + " msg=" + e.getMessage());
        }
    }

    public boolean containsKey(String docId) {
        try {
            boolean exists = collection.exists(docId).exists();
            System.out.println("CB EXISTS OK docId=" + docId + " exists=" + exists);
            return exists;
        } catch (Exception e) {
            System.out.println("CB EXISTS ERROR docId=" + docId + " msg=" + e.getMessage());
            return false;
        }
    }

    public boolean containsValueForKey(String docId) {
        try {
            GetResult result = collection.get(docId);
            JsonObject content = result.contentAsObject();
            boolean hasValue = content != null && content.containsKey("value") && content.get("value") != null;
            System.out.println("CB CONTAINS VALUE FOR KEY OK docId=" + docId + " hasValue=" + hasValue + " content=" + content);
            return hasValue;
        } catch (Exception e) {
            System.out.println("CB CONTAINS VALUE FOR KEY MISS/ERROR docId=" + docId + " msg=" + e.getMessage());
            return false;
        }
    }

    public static String docId(String region, String key) {
        return region + "::" + key;
    }
}