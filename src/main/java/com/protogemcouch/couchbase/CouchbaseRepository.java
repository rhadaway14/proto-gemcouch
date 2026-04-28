package com.protogemcouch.couchbase;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import com.protogemcouch.config.ServerConfig;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.util.DocumentKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CouchbaseRepository implements Repository {

    private static final Logger log = LoggerFactory.getLogger(CouchbaseRepository.class);

    private final ServerConfig config;

    private Cluster cluster;
    private Bucket bucket;
    private Scope scope;
    private Collection collection;

    public CouchbaseRepository(ServerConfig config) {
        this.config = config;
    }

    public void connect() {
        log.info(StructuredLog.event(
                "repository_connecting",
                "connstr", config.getCouchbaseConnectionString(),
                "bucket", config.getCouchbaseBucket(),
                "scope", config.getCouchbaseScope(),
                "collection", config.getCouchbaseCollection()
        ));

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

        log.info(StructuredLog.event("repository_connected"));
    }

    public void disconnect() {
        try {
            if (cluster != null) {
                cluster.disconnect();
                log.info(StructuredLog.event("repository_disconnected"));
            }
        } catch (Exception e) {
            log.warn(StructuredLog.event(
                    "repository_disconnect_failed",
                    "error", e.getMessage()
            ), e);
        }
    }

    @Override
    public String get(String docId) {
        try {
            GetResult result = collection.get(docId);
            JsonObject content = result.contentAsObject();

            log.info(StructuredLog.event(
                    "repository_get_ok",
                    "docId", docId
            ));

            if (content.containsKey("value")) {
                return content.getString("value");
            }
            return null;
        } catch (Exception e) {
            log.warn(StructuredLog.event(
                    "repository_get_miss_or_error",
                    "docId", docId,
                    "error", e.getMessage()
            ));
            return null;
        }
    }

    @Override
    public Map<String, String> getAll(String region, List<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            String docId = DocumentKeyUtil.docId(region, key);
            String value = get(docId);
            out.put(key, value);
        }

        log.info(StructuredLog.event(
                "repository_get_all_ok",
                "region", region,
                "key_count", keys.size()
        ));

        return out;
    }

    @Override
    public void put(String docId, String value) {
        JsonObject body = JsonObject.create().put("value", value);
        collection.upsert(docId, body);

        log.info(StructuredLog.event(
                "repository_put_ok",
                "docId", docId
        ));
    }

    @Override
    public void remove(String docId) {
        try {
            collection.remove(docId);
            log.info(StructuredLog.event(
                    "repository_remove_ok",
                    "docId", docId
            ));
        } catch (Exception e) {
            log.warn(StructuredLog.event(
                    "repository_remove_miss_or_error",
                    "docId", docId,
                    "error", e.getMessage()
            ));
        }
    }

    @Override
    public boolean containsKey(String docId) {
        try {
            boolean exists = collection.exists(docId).exists();

            log.info(StructuredLog.event(
                    "repository_contains_key_ok",
                    "docId", docId,
                    "exists", exists
            ));

            return exists;
        } catch (Exception e) {
            log.warn(StructuredLog.event(
                    "repository_contains_key_error",
                    "docId", docId,
                    "error", e.getMessage()
            ));
            return false;
        }
    }

    @Override
    public boolean containsValueForKey(String docId) {
        try {
            GetResult result = collection.get(docId);
            JsonObject content = result.contentAsObject();
            boolean hasValue = content != null && content.containsKey("value") && content.get("value") != null;

            log.info(StructuredLog.event(
                    "repository_contains_value_for_key_ok",
                    "docId", docId,
                    "has_value", hasValue
            ));

            return hasValue;
        } catch (DocumentNotFoundException e) {
            log.info(StructuredLog.event(
                    "repository_contains_value_for_key_miss",
                    "docId", docId,
                    "has_value", false
            ));

            return false;
        } catch (Exception e) {
            log.warn(StructuredLog.event(
                    "repository_contains_value_for_key_error",
                    "docId", docId,
                    "error", e.getMessage()
            ));
            return false;
        }
    }

    @Override
    public int size(String region) {
        try {
            String prefix = region + "::%";
            String statement = "SELECT RAW COUNT(1) FROM "
                    + q(config.getCouchbaseBucket()) + "."
                    + q(config.getCouchbaseScope()) + "."
                    + q(config.getCouchbaseCollection())
                    + " WHERE META().id LIKE $prefix";

            QueryResult result = cluster.query(
                    statement,
                    QueryOptions.queryOptions()
                            .parameters(JsonObject.create().put("prefix", prefix))
            );

            List<Long> rows = result.rowsAs(Long.class);
            int count = rows.isEmpty() ? 0 : rows.get(0).intValue();

            log.info(StructuredLog.event(
                    "repository_size_ok",
                    "region", region,
                    "count", count
            ));

            return count;
        } catch (Exception e) {
            log.warn(StructuredLog.event(
                    "repository_size_error",
                    "region", region,
                    "error", e.getMessage()
            ));
            return 0;
        }
    }

    @Override
    public List<String> keySet(String region) {
        try {
            String regionPrefix = region + "::";
            String likePrefix = regionPrefix + "%";

            String statement = "SELECT RAW META().id FROM "
                    + q(config.getCouchbaseBucket()) + "."
                    + q(config.getCouchbaseScope()) + "."
                    + q(config.getCouchbaseCollection())
                    + " WHERE META().id LIKE $prefix";

            QueryResult result = cluster.query(
                    statement,
                    QueryOptions.queryOptions()
                            .parameters(JsonObject.create().put("prefix", likePrefix))
            );

            List<String> ids = result.rowsAs(String.class);
            List<String> keys = new ArrayList<>();

            for (String id : ids) {
                if (id != null && id.startsWith(regionPrefix)) {
                    keys.add(id.substring(regionPrefix.length()));
                }
            }

            log.info(StructuredLog.event(
                    "repository_key_set_ok",
                    "region", region,
                    "count", keys.size()
            ));

            return keys;
        } catch (Exception e) {
            log.warn(StructuredLog.event(
                    "repository_key_set_error",
                    "region", region,
                    "error", e.getMessage()
            ));
            return new ArrayList<>();
        }
    }

    private static String q(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}