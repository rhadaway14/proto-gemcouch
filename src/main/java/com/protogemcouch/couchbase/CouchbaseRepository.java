package com.protogemcouch.couchbase;

import com.couchbase.client.core.error.CasMismatchException;
import com.couchbase.client.core.error.DocumentExistsException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.core.error.subdoc.PathExistsException;
import com.couchbase.client.core.msg.kv.DurabilityLevel;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.ExistsResult;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.InsertOptions;
import com.couchbase.client.java.kv.LookupInResult;
import com.couchbase.client.java.kv.LookupInSpec;
import com.couchbase.client.java.kv.MutateInOptions;
import com.couchbase.client.java.kv.MutateInSpec;
import com.couchbase.client.java.kv.RemoveOptions;
import com.couchbase.client.java.kv.ReplaceOptions;
import com.couchbase.client.java.kv.StoreSemantics;
import com.couchbase.client.java.kv.UpsertOptions;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.query.QueryScanConsistency;
import com.couchbase.client.java.transactions.TransactionGetResult;
import com.protogemcouch.config.ServerConfig;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.DocumentKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

public class CouchbaseRepository implements Repository {

    private static final Logger log = LoggerFactory.getLogger(CouchbaseRepository.class);

    private static final String FIELD_VALUE = "value";
    private static final String FIELD_VALUE_BASE64 = "valueBase64";
    private static final String FIELD_LENGTH = "length";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_EPOCH_MILLIS = "epochMillis";
    private static final String FIELD_OPAQUE_GEODE_TYPE_NAME = "opaqueGeodeTypeName";
    private static final String FIELD_KEYS = "keys";
    private static final String FIELD_PDX_FIELDS = "pdxFields";
    private static final String TYPE_INVALIDATED = "invalidated";
    private static final String KEYSET_META_PREFIX = "__protogemcouch::keyset::";

    // Durable-subscription persistence (1.2.0-M1): one Couchbase doc per durable client holds its
    // retained registry record (interests/CQs/timeout/away) plus its disconnect-time event queue.
    private static final String DURABLE_META_PREFIX = "__protogemcouch::durable::";
    private static final String TYPE_DURABLE_REGISTRY = "durableRegistry";
    private static final String FIELD_DURABLE_ID = "durableId";
    private static final String FIELD_TIMEOUT_SECONDS = "timeoutSeconds";
    private static final String FIELD_AWAY = "away";
    private static final String FIELD_INTERESTS = "interests";
    private static final String FIELD_CQS = "cqs";
    private static final String FIELD_QUEUE = "queue";
    private static final String FIELD_REGION = "region";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_REGEX = "regex";
    private static final String FIELD_CQ_NAME = "cqName";
    private static final String FIELD_QUERY = "query";
    private static final int DEFAULT_DURABLE_MAX_QUEUE = 100_000;

    /** Bounded retries for compare-and-swap atomic operations under concurrent contention. */
    private static final int CAS_MAX_ATTEMPTS = 32;

    private static final String TYPE_STRING = "string";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_CHARACTER = "character";
    private static final String TYPE_BYTE = "byte";
    private static final String TYPE_BYTE_ARRAY = "byteArray";
    private static final String TYPE_BOOLEAN_ARRAY = "booleanArray";
    private static final String TYPE_CHAR_ARRAY = "charArray";
    private static final String TYPE_SHORT_ARRAY = "shortArray";
    private static final String TYPE_INT_ARRAY = "intArray";
    private static final String TYPE_LONG_ARRAY = "longArray";
    private static final String TYPE_FLOAT_ARRAY = "floatArray";
    private static final String TYPE_DOUBLE_ARRAY = "doubleArray";
    private static final String TYPE_STRING_ARRAY = "stringArray";
    private static final String TYPE_STRING_ARRAY_LIST = "stringArrayList";
    private static final String TYPE_STRING_HASH_MAP = "stringHashMap";
    private static final String TYPE_STRING_OBJECT_HASH_MAP = "stringObjectHashMap";
    private static final String TYPE_JAVA_SERIALIZED_OBJECT = "javaSerializedObject";
    private static final String TYPE_OBJECT_ARRAY = "objectArray";
    private static final String TYPE_OBJECT_ARRAY_LIST = "objectArrayList";
    private static final String TYPE_OPAQUE_GEODE_VALUE = "opaqueGeodeValue";
    private static final String TYPE_PDX_INSTANCE = "pdxInstance";
    private static final String TYPE_SHORT = "short";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_DOUBLE = "double";
    private static final String TYPE_DATE = "date";
    // Nested complex types inside a HashMap<String,Object> value (recursive, queryable). Distinct
    // from the top-level base64 OBJECT_ARRAY / OBJECT_ARRAY_LIST tags above, which preserve opaque
    // Geode bytes rather than a structured graph.
    private static final String TYPE_BIG_INTEGER = "bigInteger";
    private static final String TYPE_BIG_DECIMAL = "bigDecimal";
    private static final String TYPE_UUID = "uuid";
    private static final String TYPE_ENUM = "enum";
    private static final String FIELD_ENUM_CLASS = "enumClass";
    private static final String TYPE_NESTED_OBJECT_ARRAY = "nestedObjectArray";
    private static final String TYPE_NESTED_LIST = "nestedList";
    private static final String TYPE_NESTED_MAP = "nestedMap";

    private final ServerConfig config;

    private Cluster cluster;
    private Bucket bucket;
    private Scope scope;
    private Collection collection;

    // Optional PDX scalar-field extractor (installed when OQL pushdown is enabled): turns a PDX
    // instance's wire bytes into its scalar fields, written as a queryable `pdxFields` sidecar.
    private volatile Function<byte[], Map<String, Object>> pdxScalarExtractor;

    // Entry time-to-live config (Couchbase document expiry): default + per-region overrides + idle
    // (entry-idle-time) vs ttl (entry-time-to-live) mode. Drives write expiry, idle get-and-touch,
    // and keyset eviction.
    private TtlConfig ttlConfig = new TtlConfig(0, java.util.Map.of(), false);

    // Couchbase write durability applied to all value writes (CB_DURABILITY). NONE = ack on memory
    // (the default / fastest); higher levels require a suitably-replicated cluster.
    private DurabilityLevel writeDurability = DurabilityLevel.NONE;

    // Couchbase's hard per-document ceiling is 20 MiB; a value whose encoded document exceeds it is
    // rejected by the server in an opaque way (and could leave the keyset inconsistent). Reject such
    // writes up front (CB_MAX_VALUE_BYTES) with a clean ServerOperationException instead.
    static final long DEFAULT_MAX_VALUE_BYTES = 20L * 1024 * 1024;
    private long maxValueBytes = DEFAULT_MAX_VALUE_BYTES;

    // Durable-subscription persistence is opt-in (DURABLE_PERSISTENCE, default off) for safe rollout:
    // when off, the durable repository methods are no-ops (single-instance, in-memory behavior is
    // unchanged). DURABLE_MAX_QUEUE bounds each persisted queue (oldest dropped on overflow).
    private boolean durablePersistenceEnabled = false;
    private int durableMaxQueue = DEFAULT_DURABLE_MAX_QUEUE;

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

        long kvTimeoutMs = parsePositiveLongOrDefault(System.getenv("CB_KV_TIMEOUT_MS"), 5_000L);
        long connectTimeoutMs = parsePositiveLongOrDefault(System.getenv("CB_CONNECT_TIMEOUT_MS"), 10_000L);

        this.ttlConfig = TtlConfig.fromEnv();
        log.info(StructuredLog.event(
                "repository_ttl_configured",
                "defaultSeconds", ttlConfig.defaultSeconds(),
                "perRegion", ttlConfig.perRegionSeconds(),
                "mode", ttlConfig.idle() ? "idle" : "ttl",
                "enabled", ttlConfig.anyEnabled()));

        this.writeDurability = parseDurability(System.getenv("CB_DURABILITY"));
        log.info(StructuredLog.event("repository_durability_configured", "level", writeDurability.name()));

        this.maxValueBytes = parseMaxValueBytes(System.getenv("CB_MAX_VALUE_BYTES"));
        log.info(StructuredLog.event("repository_max_value_bytes_configured",
                "maxValueBytes", maxValueBytes, "enabled", maxValueBytes > 0));

        this.durablePersistenceEnabled = Boolean.parseBoolean(System.getenv("DURABLE_PERSISTENCE"));
        this.durableMaxQueue =
                (int) parsePositiveLongOrDefault(System.getenv("DURABLE_MAX_QUEUE"), DEFAULT_DURABLE_MAX_QUEUE);
        log.info(StructuredLog.event("repository_durable_persistence_configured",
                "enabled", durablePersistenceEnabled, "maxQueue", durableMaxQueue));

        log.info(StructuredLog.event(
                "repository_timeouts_configured",
                "kvTimeoutMs", kvTimeoutMs,
                "connectTimeoutMs", connectTimeoutMs
        ));

        boolean tlsEnabled = couchbaseTlsEnabled(
                config.getCouchbaseConnectionString(), System.getenv("CB_TLS_ENABLED"));
        String certPath = System.getenv("CB_TLS_CERT_PATH");
        boolean verifyHostname = !"false".equalsIgnoreCase(System.getenv("CB_TLS_VERIFY_HOSTNAME"));

        ClusterEnvironment.Builder envBuilder = ClusterEnvironment.builder()
                .timeoutConfig(tc -> tc
                        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .kvTimeout(Duration.ofMillis(kvTimeoutMs)));

        if (tlsEnabled) {
            envBuilder.securityConfig(sc -> {
                sc.enableTls(true);
                sc.enableHostnameVerification(verifyHostname);
                if (certPath != null && !certPath.isBlank()) {
                    sc.trustCertificate(java.nio.file.Path.of(certPath));
                }
            });
            log.info(StructuredLog.event(
                    "repository_tls_configured",
                    "enabled", true,
                    "trustCertificate", certPath == null ? "<default>" : certPath,
                    "verifyHostname", verifyHostname
            ));
        }

        ClusterEnvironment env = envBuilder.build();

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

    /**
     * TLS to Couchbase is used when the connection string uses the {@code couchbases://} scheme or
     * {@code CB_TLS_ENABLED=true}. Package-private for testing.
     */
    static boolean couchbaseTlsEnabled(String connectionString, String cbTlsEnabledEnv) {
        boolean secureScheme = connectionString != null
                && connectionString.trim().toLowerCase().startsWith("couchbases://");
        return secureScheme || Boolean.parseBoolean(cbTlsEnabledEnv);
    }

    /**
     * Parse a positive millisecond timeout, falling back to the default for an unset, blank,
     * non-numeric, or non-positive value. Package-private for testing.
     */
    static long parsePositiveLongOrDefault(String rawValue, long defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(rawValue.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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

    // Write-option builders that attach the region's configured entry TTL (Couchbase document
    // expiry) when one applies; otherwise they return default options (no expiry).
    private UpsertOptions upsertOptions(String region) {
        UpsertOptions options = UpsertOptions.upsertOptions();
        Duration ttl = ttlConfig.durationFor(region);
        if (ttl != null) {
            options.expiry(ttl);
        }
        if (writeDurability != DurabilityLevel.NONE) {
            options.durability(writeDurability);
        }
        return options;
    }

    private InsertOptions insertOptions(String region) {
        InsertOptions options = InsertOptions.insertOptions();
        Duration ttl = ttlConfig.durationFor(region);
        if (ttl != null) {
            options.expiry(ttl);
        }
        if (writeDurability != DurabilityLevel.NONE) {
            options.durability(writeDurability);
        }
        return options;
    }

    private ReplaceOptions replaceOptions(String region, long cas) {
        ReplaceOptions options = ReplaceOptions.replaceOptions().cas(cas);
        Duration ttl = ttlConfig.durationFor(region);
        if (ttl != null) {
            options.expiry(ttl);
        }
        if (writeDurability != DurabilityLevel.NONE) {
            options.durability(writeDurability);
        }
        return options;
    }

    private RemoveOptions removeOptions() {
        RemoveOptions options = RemoveOptions.removeOptions();
        if (writeDurability != DurabilityLevel.NONE) {
            options.durability(writeDurability);
        }
        return options;
    }

    private RemoveOptions removeOptions(long cas) {
        RemoveOptions options = RemoveOptions.removeOptions().cas(cas);
        if (writeDurability != DurabilityLevel.NONE) {
            options.durability(writeDurability);
        }
        return options;
    }

    /** Parse CB_DURABILITY into a Couchbase durability level (unknown/blank -> NONE). */
    static DurabilityLevel parseDurability(String raw) {
        if (raw == null || raw.isBlank()) {
            return DurabilityLevel.NONE;
        }
        switch (raw.trim().toLowerCase().replace("_", "")) {
            case "majority":
                return DurabilityLevel.MAJORITY;
            case "majorityandpersisttoactive":
                return DurabilityLevel.MAJORITY_AND_PERSIST_TO_ACTIVE;
            case "persisttomajority":
                return DurabilityLevel.PERSIST_TO_MAJORITY;
            default:
                return DurabilityLevel.NONE;
        }
    }

    /**
     * Parse CB_MAX_VALUE_BYTES into a max encoded-document size. Unset/blank/unparseable falls back to
     * {@link #DEFAULT_MAX_VALUE_BYTES} (Couchbase's 20 MiB ceiling); {@code 0} (or negative) disables
     * the limit (writes then rely on the backend to reject oversized documents).
     */
    static long parseMaxValueBytes(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_VALUE_BYTES;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed <= 0 ? 0L : parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_VALUE_BYTES;
        }
    }

    /**
     * Encode {@code value} for storage and enforce the configured max value size, throwing a
     * {@link ValueTooLargeException} (surfaced to the client as a {@code ServerOperationException})
     * before any backend write when the encoded document would exceed the limit — so an oversized
     * value never reaches Couchbase and never updates the region's keyset.
     */
    @Override
    public void setPdxScalarExtractor(Function<byte[], Map<String, Object>> extractor) {
        this.pdxScalarExtractor = extractor;
    }

    private JsonObject encodeValueChecked(StoredValue value) {
        JsonObject body = encodeStoredValue(value);
        addPdxQueryableSidecar(body, value);
        if (maxValueBytes > 0) {
            long size = body.toString().getBytes(StandardCharsets.UTF_8).length;
            if (size > maxValueBytes) {
                throw new ValueTooLargeException(size, maxValueBytes);
            }
        }
        return body;
    }

    /**
     * For a PDX value, when a scalar-field extractor is installed (pushdown enabled), add a queryable
     * {@code pdxFields} sidecar of bare scalars next to the opaque PDX bytes, so N1QL can filter PDX
     * documents by field. Best-effort: a missing/empty extraction leaves no sidecar (the document is then
     * filtered the slow, correct way). The opaque {@code valueBase64} is untouched, so reads are exact.
     */
    private void addPdxQueryableSidecar(JsonObject body, StoredValue value) {
        Function<byte[], Map<String, Object>> extractor = pdxScalarExtractor;
        if (extractor == null || value == null || value.type() != StoredValue.Type.PDX_INSTANCE) {
            return;
        }
        Map<String, Object> scalars = extractor.apply(value.asPdxInstanceValue());
        if (scalars == null || scalars.isEmpty()) {
            return;
        }
        JsonObject sidecar = JsonObject.create();
        for (Map.Entry<String, Object> entry : scalars.entrySet()) {
            Object jsonScalar = pdxSidecarScalar(entry.getValue());
            if (entry.getKey() != null && jsonScalar != null) {
                sidecar.put(entry.getKey(), jsonScalar);
            }
        }
        if (!sidecar.isEmpty()) {
            body.put(FIELD_PDX_FIELDS, sidecar);
        }
    }

    /**
     * Convert a PDX scalar to a JSON-storable form for the sidecar, chosen so the N1QL predicate's
     * {@code TO_STRING}/{@code TO_NUMBER} comparisons stay a superset of the in-shim matcher's
     * {@code String.valueOf}/numeric comparisons: numbers as numbers, boolean as boolean, and
     * String/Character/Date by their string form.
     */
    private static Object pdxSidecarScalar(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof String) {
            return value;
        }
        // Character, Date, and any other readable scalar: compare by string form (matches the matcher).
        return String.valueOf(value);
    }

    /** The region embedded in a value docId ({@code region::key}), or null if unparseable. */
    private static String regionOf(String docId) {
        ParsedDocumentKey parsed = parseDocumentKey(docId);
        return parsed == null ? null : parsed.region();
    }

    @Override
    public StoredValue get(String docId) {
        try {
            String region = regionOf(docId);
            // In idle mode (entry-idle-time), a read also refreshes the document's expiry via
            // get-and-touch, so frequently-accessed entries stay alive. In ttl mode, a plain read.
            GetResult result;
            Duration idleTtl = ttlConfig.idle() ? ttlConfig.durationFor(region) : null;
            if (idleTtl != null) {
                result = collection.getAndTouch(docId, idleTtl);
            } else {
                result = collection.get(docId);
            }
            JsonObject content = result.contentAsObject();

            log.info(StructuredLog.event(
                    "repository_get_ok",
                    "docId", docId
            ));

            return decodeStoredValue(content);
        } catch (DocumentNotFoundException e) {
            log.info(StructuredLog.event(
                    "repository_get_miss",
                    "docId", docId
            ));
            return null;
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_get_error",
                    "docId", docId,
                    "error", e.getMessage()
            ), e);
            throw new RepositoryException("get failed for docId=" + docId, e);
        }
    }

    @Override
    public Map<String, StoredValue> getAll(String region, List<String> keys) {
        Map<String, StoredValue> out = new LinkedHashMap<>();

        for (String key : keys) {
            String docId = DocumentKeyUtil.docId(region, key);
            StoredValue value = get(docId);
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
    public void put(String docId, StoredValue value) {
        if (value == null) {
            log.warn(StructuredLog.event(
                    "repository_put_skipped_null_value",
                    "docId", docId
            ));
            return;
        }

        JsonObject body = encodeValueChecked(value);

        try {
            collection.upsert(docId, body, upsertOptions(regionOf(docId)));
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_put_error",
                    "docId", docId,
                    "valueType", value.type(),
                    "error", e.getMessage()
            ), e);
            throw new RepositoryException("put failed for docId=" + docId, e);
        }

        updateKeySetMetadataForDocId(docId, true);

        log.info(StructuredLog.event(
                "repository_put_ok",
                "docId", docId,
                "valueType", value.type()
        ));
    }

    /**
     * Apply a committed transaction's writes atomically via a Couchbase multi-document ACID
     * transaction: every value document and the affected per-region keyset-metadata documents are
     * inserted/replaced/removed inside one {@code cluster.transactions().run(...)}, so a mid-apply
     * failure rolls the whole commit back (nothing is persisted) and surfaces as a
     * {@link RepositoryException}.
     *
     * <p>Notes: transactional inserts/replaces do not carry per-document expiry, so per-region TTL is
     * not applied to writes committed this way; transaction durability is Couchbase's transaction
     * default. Each buffered op targets a distinct document id (the buffer is keyed by id), so no
     * document is touched twice within the transaction.
     */
    @Override
    public void commitAtomically(List<WriteOp> ops) {
        if (ops == null || ops.isEmpty()) {
            return;
        }

        // Net keyset change per region, applied inside the same transaction so size()/keySet() stay
        // consistent with the value writes.
        Map<String, TreeSet<String>> adds = new LinkedHashMap<>();
        Map<String, TreeSet<String>> removes = new LinkedHashMap<>();
        for (WriteOp op : ops) {
            ParsedDocumentKey parsed = parseDocumentKey(op.docId());
            if (parsed == null) {
                continue;
            }
            if (op.remove()) {
                removes.computeIfAbsent(parsed.region(), r -> new TreeSet<>()).add(parsed.key());
            } else {
                adds.computeIfAbsent(parsed.region(), r -> new TreeSet<>()).add(parsed.key());
            }
        }

        try {
            cluster.transactions().run(ctx -> {
                for (WriteOp op : ops) {
                    if (op.remove()) {
                        try {
                            ctx.remove(ctx.get(collection, op.docId()));
                        } catch (DocumentNotFoundException alreadyGone) {
                            // Nothing to remove; the net keyset removal below still applies.
                        }
                    } else if (op.value() != null) {
                        JsonObject body = encodeValueChecked(op.value());
                        try {
                            ctx.replace(ctx.get(collection, op.docId()), body);
                        } catch (DocumentNotFoundException absent) {
                            ctx.insert(collection, op.docId(), body);
                        }
                    }
                }

                // Recompute each affected region's keyset within the transaction.
                java.util.Set<String> regions = new java.util.LinkedHashSet<>();
                regions.addAll(adds.keySet());
                regions.addAll(removes.keySet());
                for (String region : regions) {
                    String metadataDocId = keySetMetadataDocId(region);
                    TreeSet<String> keys = new TreeSet<>();
                    TransactionGetResult metaGet = null;
                    try {
                        metaGet = ctx.get(collection, metadataDocId);
                        JsonArray existingKeys = metaGet.contentAsObject().getArray(FIELD_KEYS);
                        if (existingKeys != null) {
                            for (Object rawKey : existingKeys) {
                                if (rawKey != null) {
                                    keys.add(String.valueOf(rawKey));
                                }
                            }
                        }
                    } catch (DocumentNotFoundException absent) {
                        metaGet = null;
                    }
                    keys.addAll(adds.getOrDefault(region, new TreeSet<>()));
                    keys.removeAll(removes.getOrDefault(region, new TreeSet<>()));

                    JsonArray keyArray = JsonArray.create();
                    for (String currentKey : keys) {
                        keyArray.add(currentKey);
                    }
                    JsonObject metadata = JsonObject.create()
                            .put(FIELD_TYPE, "keySetMetadata")
                            .put("region", region)
                            .put(FIELD_KEYS, keyArray)
                            .put(FIELD_LENGTH, keys.size());

                    if (metaGet == null) {
                        ctx.insert(collection, metadataDocId, metadata);
                    } else {
                        ctx.replace(metaGet, metadata);
                    }
                }
            });
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_commit_atomic_error",
                    "op_count", ops.size(),
                    "error", e.getMessage()), e);
            throw new RepositoryException("atomic commit failed for " + ops.size() + " operations", e);
        }

        log.info(StructuredLog.event(
                "repository_commit_atomic_ok",
                "op_count", ops.size(),
                "regions", adds.size()));
    }

    @Override
    public void putAll(String region, Map<String, StoredValue> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        // Issue all value upserts concurrently rather than one blocking round-trip per entry.
        List<CompletableFuture<?>> upserts = new ArrayList<>(values.size());
        List<String> storedKeys = new ArrayList<>(values.size());

        for (Map.Entry<String, StoredValue> entry : values.entrySet()) {
            StoredValue value = entry.getValue();
            if (value == null) {
                log.warn(StructuredLog.event(
                        "repository_put_all_skipped_null_value",
                        "region", region,
                        "key", entry.getKey()
                ));
                continue;
            }

            String docId = DocumentKeyUtil.docId(region, entry.getKey());
            storedKeys.add(entry.getKey());
            try {
                // Encode (incl. the max-value-size check) inside the try so an oversized entry is a
                // per-key failure recorded below, not an abort of the whole batch.
                JsonObject body = encodeValueChecked(value);
                upserts.add(collection.async().upsert(docId, body, upsertOptions(region)));
            } catch (RuntimeException submitError) {
                // A synchronous failure (e.g. an oversized value or an invalid/over-long key) is
                // recorded per key too.
                CompletableFuture<Object> failed = new CompletableFuture<>();
                failed.completeExceptionally(submitError);
                upserts.add(failed);
            }
        }

        if (upserts.isEmpty()) {
            return;
        }

        // Wait for every upsert (not fail-fast) and record per-key outcomes, so a partial failure
        // still persists the entries that succeeded and counts them in the keyset, rather than
        // discarding the whole batch.
        List<String> succeeded = new ArrayList<>(storedKeys.size());
        LinkedHashMap<String, String> failures = new LinkedHashMap<>();
        for (int i = 0; i < upserts.size(); i++) {
            try {
                upserts.get(i).join();
                succeeded.add(storedKeys.get(i));
            } catch (Exception e) {
                failures.put(storedKeys.get(i), rootCauseMessage(e));
            }
        }

        // Add only the successfully-stored keys to the region's keyset metadata (one batch write).
        if (!succeeded.isEmpty()) {
            updateKeySetMetadataBatch(region, succeeded);
        }

        log.info(StructuredLog.event(
                "repository_put_all_ok",
                "region", region,
                "stored_count", succeeded.size(),
                "failed_count", failures.size()
        ));

        if (!failures.isEmpty()) {
            log.error(StructuredLog.event(
                    "repository_put_all_partial_failure",
                    "region", region,
                    "stored_count", succeeded.size(),
                    "failed_count", failures.size(),
                    "failedKeys", failures.keySet()
            ));
            throw new RepositoryException(
                    "putAll partially failed for region=" + region + ": " + failures.size() + " of "
                            + upserts.size() + " entries failed (succeeded: " + succeeded.size()
                            + "); failed keys=" + failures.keySet());
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private void updateKeySetMetadataBatch(String region, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        mutateKeySetMetadata(region, current -> current.addAll(keys), "batch_add:" + keys.size());
    }

    @Override
    public void remove(String docId) {
        try {
            collection.remove(docId, removeOptions());
            updateKeySetMetadataForDocId(docId, false);

            log.info(StructuredLog.event(
                    "repository_remove_ok",
                    "docId", docId
            ));
        } catch (DocumentNotFoundException e) {
            updateKeySetMetadataForDocId(docId, false);

            log.info(StructuredLog.event(
                    "repository_remove_miss",
                    "docId", docId
            ));
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_remove_error",
                    "docId", docId,
                    "error", e.getMessage()
            ), e);
            throw new RepositoryException("remove failed for docId=" + docId, e);
        }
    }

    /**
     * Invalidate an entry: store a value-less marker so the key stays present (kept in the keyset and
     * visible to {@code containsKey}) but {@code get}/{@code containsValueForKey} report no value. The
     * {@code {"type":"invalidated"}} marker decodes to a null {@link StoredValue}.
     */
    @Override
    public void invalidate(String docId) {
        try {
            collection.upsert(docId, JsonObject.create().put(FIELD_TYPE, "invalidated"),
                    upsertOptions(regionOf(docId)));
            updateKeySetMetadataForDocId(docId, true);
            log.info(StructuredLog.event("repository_invalidate_ok", "docId", docId));
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_invalidate_error", "docId", docId, "error", e.getMessage()), e);
            throw new RepositoryException("invalidate failed for docId=" + docId, e);
        }
    }

    /** Remove every entry's value in a region and clear the region's keyset metadata in one shot. */
    @Override
    public void clear(String region) {
        List<String> keys = keySet(region);
        for (String key : keys) {
            String docId = DocumentKeyUtil.docId(region, key);
            try {
                collection.remove(docId, removeOptions());
            } catch (DocumentNotFoundException ignored) {
                // already gone
            } catch (Exception e) {
                log.error(StructuredLog.event(
                        "repository_clear_error", "region", region, "docId", docId,
                        "error", e.getMessage()), e);
                throw new RepositoryException("clear failed for region=" + region, e);
            }
        }
        mutateKeySetMetadata(region, TreeSet::clear, "clear:" + keys.size());
        log.info(StructuredLog.event("repository_clear_ok", "region", region, "removed", keys.size()));
    }

    /**
     * Atomic insert-if-absent using Couchbase {@code insert} (fails if the document already exists).
     * Returns {@code null} when the value was inserted, or the existing value when the key was
     * already present (nothing stored). This is genuinely atomic across concurrent writers and shim
     * replicas, unlike a get-then-put.
     */
    @Override
    public StoredValue putIfAbsent(String docId, StoredValue value) {
        if (value == null) {
            return get(docId);
        }
        JsonObject body = encodeValueChecked(value);
        try {
            collection.insert(docId, body, insertOptions(regionOf(docId)));
            updateKeySetMetadataForDocId(docId, true);
            log.info(StructuredLog.event(
                    "repository_put_if_absent_inserted", "docId", docId, "valueType", value.type()));
            return null;
        } catch (DocumentExistsException e) {
            log.info(StructuredLog.event("repository_put_if_absent_present", "docId", docId));
            return get(docId);
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_put_if_absent_error", "docId", docId, "error", e.getMessage()), e);
            throw new RepositoryException("putIfAbsent failed for docId=" + docId, e);
        }
    }

    /**
     * Atomic replace-if-present: read the current document (capturing its CAS) and replace only if
     * unchanged, retrying on a concurrent change. Returns the previous value, or {@code null} if the
     * key was (or became) absent — in which case nothing is stored. The keyset is unchanged because
     * the key was already present.
     */
    @Override
    public StoredValue replace(String docId, StoredValue value) {
        if (value == null) {
            return get(docId);
        }
        JsonObject body = encodeValueChecked(value);
        for (int attempt = 1; attempt <= CAS_MAX_ATTEMPTS; attempt++) {
            try {
                GetResult existing;
                try {
                    existing = collection.get(docId);
                } catch (DocumentNotFoundException notFound) {
                    return null; // absent: replace is a no-op
                }
                StoredValue previous = decodeStoredValue(existing.contentAsObject());
                collection.replace(docId, body, replaceOptions(regionOf(docId), existing.cas()));
                log.info(StructuredLog.event(
                        "repository_replace_ok", "docId", docId, "valueType", value.type(), "attempt", attempt));
                return previous;
            } catch (CasMismatchException conflict) {
                // changed concurrently: re-read and retry
            } catch (DocumentNotFoundException removedConcurrently) {
                return null;
            } catch (Exception e) {
                log.error(StructuredLog.event(
                        "repository_replace_error", "docId", docId, "error", e.getMessage()), e);
                throw new RepositoryException("replace failed for docId=" + docId, e);
            }
        }
        log.warn(StructuredLog.event("repository_replace_cas_exhausted", "docId", docId));
        return get(docId);
    }

    /**
     * Atomic compare-and-replace: replace only if the current value equals {@code expected}, guarded
     * by CAS so a concurrent change cannot slip between the compare and the write. Returns whether
     * the replace happened.
     */
    @Override
    public boolean replace(String docId, StoredValue expected, StoredValue newValue) {
        if (newValue == null) {
            return false;
        }
        JsonObject body = encodeValueChecked(newValue);
        for (int attempt = 1; attempt <= CAS_MAX_ATTEMPTS; attempt++) {
            try {
                GetResult existing;
                try {
                    existing = collection.get(docId);
                } catch (DocumentNotFoundException notFound) {
                    return false;
                }
                StoredValue current = decodeStoredValue(existing.contentAsObject());
                if (current == null || !current.equals(expected)) {
                    return false;
                }
                collection.replace(docId, body, replaceOptions(regionOf(docId), existing.cas()));
                log.info(StructuredLog.event(
                        "repository_compare_replace_ok", "docId", docId, "attempt", attempt));
                return true;
            } catch (CasMismatchException conflict) {
                // changed concurrently: re-read and retry
            } catch (DocumentNotFoundException removedConcurrently) {
                return false;
            } catch (Exception e) {
                log.error(StructuredLog.event(
                        "repository_compare_replace_error", "docId", docId, "error", e.getMessage()), e);
                throw new RepositoryException("compare-replace failed for docId=" + docId, e);
            }
        }
        log.warn(StructuredLog.event("repository_compare_replace_cas_exhausted", "docId", docId));
        return false;
    }

    /**
     * Atomic compare-and-remove: remove only if the current value equals {@code expected}, guarded by
     * CAS. Returns whether the remove happened.
     */
    @Override
    public boolean removeIfValue(String docId, StoredValue expected) {
        for (int attempt = 1; attempt <= CAS_MAX_ATTEMPTS; attempt++) {
            try {
                GetResult existing;
                try {
                    existing = collection.get(docId);
                } catch (DocumentNotFoundException notFound) {
                    return false;
                }
                StoredValue current = decodeStoredValue(existing.contentAsObject());
                if (current == null || !current.equals(expected)) {
                    return false;
                }
                collection.remove(docId, removeOptions(existing.cas()));
                updateKeySetMetadataForDocId(docId, false);
                log.info(StructuredLog.event(
                        "repository_compare_remove_ok", "docId", docId, "attempt", attempt));
                return true;
            } catch (CasMismatchException conflict) {
                // changed concurrently: re-read and retry
            } catch (DocumentNotFoundException removedConcurrently) {
                return false;
            } catch (Exception e) {
                log.error(StructuredLog.event(
                        "repository_compare_remove_error", "docId", docId, "error", e.getMessage()), e);
                throw new RepositoryException("compare-remove failed for docId=" + docId, e);
            }
        }
        log.warn(StructuredLog.event("repository_compare_remove_cas_exhausted", "docId", docId));
        return false;
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
            log.error(StructuredLog.event(
                    "repository_contains_key_error",
                    "docId", docId,
                    "error", e.getMessage()
            ), e);
            throw new RepositoryException("containsKey failed for docId=" + docId, e);
        }
    }

    @Override
    public boolean containsValueForKey(String docId) {
        try {
            GetResult result = collection.get(docId);
            JsonObject content = result.contentAsObject();

            StoredValue value = decodeStoredValue(content);
            boolean hasValue = value != null;

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
            log.error(StructuredLog.event(
                    "repository_contains_value_for_key_error",
                    "docId", docId,
                    "error", e.getMessage()
            ), e);
            throw new RepositoryException("containsValueForKey failed for docId=" + docId, e);
        }
    }

    @Override
    public int size(String region) {
        List<String> keys = keySet(region);
        int count = keys.size();

        log.info(StructuredLog.event(
                "repository_size_ok",
                "region", region,
                "count", count
        ));

        return count;
    }

    @Override
    public List<String> keySet(String region) {
        try {
            String metadataDocId = keySetMetadataDocId(region);

            GetResult result = collection.get(metadataDocId);
            JsonObject content = result.contentAsObject();
            JsonArray rawKeys = content.getArray(FIELD_KEYS);

            List<String> keys = new ArrayList<>();

            if (rawKeys != null) {
                for (Object rawKey : rawKeys) {
                    if (rawKey != null) {
                        keys.add(String.valueOf(rawKey));
                    }
                }
            }

            // Keyset eviction: when a TTL applies to this region, value docs can expire out from
            // under the keyset metadata. Verify which keys still exist and prune the expired ones so
            // size/keySet stay correct.
            if (ttlConfig.enabledFor(region) && !keys.isEmpty()) {
                keys = pruneExpiredKeys(region, keys);
            }

            log.info(StructuredLog.event(
                    "repository_key_set_ok",
                    "region", region,
                    "metadataDocId", metadataDocId,
                    "count", keys.size(),
                    "keys", keys
            ));

            return keys;
        } catch (DocumentNotFoundException e) {
            log.info(StructuredLog.event(
                    "repository_key_set_miss",
                    "region", region,
                    "metadataDocId", keySetMetadataDocId(region),
                    "count", 0
            ));

            return new ArrayList<>();
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_key_set_error",
                    "region", region,
                    "metadataDocId", keySetMetadataDocId(region),
                    "error", e.getMessage()
            ), e);
            throw new RepositoryException("keySet failed for region=" + region, e);
        }
    }

    /** A simple field name safe to embed as an N1QL identifier (matches the parser's field grammar). */
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * OQL pushdown via N1QL. Builds a region-scoped query ({@code META().id LIKE "region::%"}) whose
     * WHERE is a deliberately <em>loose superset</em> of the predicates, so it can only narrow the
     * candidate set, never drop a true match (the caller's matcher re-filters authoritatively). Each
     * predicate is evaluated against both the object-map envelope path ({@code value.<f>.value}) and the
     * bare string-map path ({@code value.<f>}):
     *
     * <ul>
     *   <li><b>string equality</b> compares by string form ({@code TO_STRING(...) = $v}) so it matches
     *       regardless of the scalar's JSON type; a numeric-equality branch is OR-ed in for numeric-looking
     *       literals (covers whole-number doubles where {@code TO_STRING} and {@code String.valueOf} differ);</li>
     *   <li><b>numeric comparison</b> uses {@code TO_NUMBER(...) <op> $n}; since OQL parses numeric
     *       fields with {@code Double.parseDouble} too, a non-numeric scalar can never be a true match —
     *       but to stay a guaranteed superset, any string-typed scalar ({@code TYPE(...) = "string"}) is
     *       OR-ed in and left for the matcher to decide.</li>
     * </ul>
     *
     * <p>Non-map documents (PDX / serialized / scalar) whose fields N1QL cannot introspect are OR-ed in
     * unconditionally, so a PDX match is never dropped. Returns {@link Optional#empty()} on any problem
     * (no index, query error, bad field) so the caller falls back to the full-region scan — pushdown is
     * a pure performance optimization.
     */
    @Override
    public Optional<List<StoredValue>> queryPushdownByPredicates(
            String region, List<OqlQuery.FieldPredicate> predicates, int limit) {
        boolean noPredicates = predicates == null || predicates.isEmpty();
        // No predicates is only worth a backend round-trip when there's a row cap to push (a LIMIT with
        // no WHERE) — otherwise an unbounded region scan via N1QL has no edge over keySet + getAll.
        if (noPredicates && limit <= 0) {
            return Optional.empty();
        }

        JsonObject params = JsonObject.create();
        params.put("prefix", DocumentKeyUtil.regionPrefix(region) + "%");

        String where;
        if (noPredicates) {
            // Region-scoped LIMIT (no WHERE): the matcher accepts every live value, so exclude only the
            // invalidated markers (which decode to null) to keep the capped page all-matching.
            where = "META(c).id LIKE $prefix AND c.`type` != \"" + TYPE_INVALIDATED + "\"";
        } else {
            where = buildPredicateWhere(predicates, params);
            if (where == null) {
                return Optional.empty(); // an unvalidated field name slipped through
            }
        }

        String keyspace = "`" + collection.bucketName() + "`.`" + collection.scopeName()
                + "`.`" + collection.name() + "`";
        String statement = "SELECT RAW c FROM " + keyspace + " c WHERE " + where;
        if (limit > 0) {
            statement += " LIMIT " + limit;
        }

        return executePushdown(statement, params, region, noPredicates ? 0 : predicates.size());
    }

    /** Build the PDX-aware WHERE for a non-empty predicate list, binding params; null on a bad field. */
    private String buildPredicateWhere(List<OqlQuery.FieldPredicate> predicates, JsonObject params) {
        // Two parallel predicate sets sharing the same bound params: one over the map paths
        // (object-map `value.f.value` + string-map `value.f`), one over the PDX scalar sidecar
        // (`pdxFields.f`). A map doc matches the first; a PDX doc matches the second (or has no sidecar);
        // every other opaque doc is swept in unconditionally and re-filtered by the shim.
        StringBuilder mapPreds = new StringBuilder();
        StringBuilder pdxPreds = new StringBuilder();
        for (int i = 0; i < predicates.size(); i++) {
            OqlQuery.FieldPredicate predicate = predicates.get(i);
            String field = predicate.field();
            if (field == null || !SAFE_FIELD.matcher(field).matches()) {
                return null; // defense-in-depth: never interpolate an unvalidated identifier
            }
            String envelopePath = "c.`value`.`" + field + "`.`value`"; // object-map scalar
            String barePath = "c.`value`.`" + field + "`";             // string-map scalar
            String pdxPath = "c.`pdxFields`.`" + field + "`";          // PDX sidecar scalar
            if (mapPreds.length() > 0) {
                mapPreds.append(" AND ");
                pdxPreds.append(" AND ");
            }
            if (predicate.numeric()) {
                String nParam = "n" + i;
                params.put(nParam, predicate.number());
                String op = predicate.op().symbol();
                mapPreds.append(numericFragment(op, nParam, envelopePath, barePath));
                pdxPreds.append(numericFragment(op, nParam, pdxPath));
            } else {
                String vParam = "v" + i;
                params.put(vParam, predicate.text());
                Double numeric = parseNumericOrNull(predicate.text());
                String nParam = null;
                if (numeric != null) {
                    nParam = "n" + i;
                    params.put(nParam, numeric);
                }
                mapPreds.append(stringFragment(vParam, nParam, envelopePath, barePath));
                pdxPreds.append(stringFragment(vParam, nParam, pdxPath));
            }
        }

        return "META(c).id LIKE $prefix AND ("
                + " (" + mapPreds + ")"
                + " OR (c.`type` = \"" + TYPE_PDX_INSTANCE + "\" AND ((" + pdxPreds
                + ") OR c.`pdxFields` IS MISSING))"
                + " OR c.`type` NOT IN [\"" + TYPE_STRING_OBJECT_HASH_MAP + "\", \""
                + TYPE_STRING_HASH_MAP + "\", \"" + TYPE_PDX_INSTANCE + "\"] )";
    }

    /** Run a pushdown statement (REQUEST_PLUS), decode rows to candidate values; empty on any failure. */
    private Optional<List<StoredValue>> executePushdown(
            String statement, JsonObject params, String region, int predicateCount) {
        try {
            // REQUEST_PLUS so the query observes every mutation up to now — matching the KV scan path's
            // read-your-writes behavior. Without it N1QL defaults to not_bounded and a query right after
            // a write can miss it, which would make pushdown drop true matches (a correctness change).
            QueryResult result = cluster.query(statement, QueryOptions.queryOptions()
                    .parameters(params)
                    .readonly(true)
                    .scanConsistency(QueryScanConsistency.REQUEST_PLUS));
            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            List<StoredValue> candidates = new ArrayList<>(rows.size());
            for (JsonObject row : rows) {
                StoredValue value = decodeStoredValue(row);
                if (value != null) {
                    candidates.add(value);
                }
            }
            log.info(StructuredLog.event(
                    "repository_query_pushdown_ok", "region", region,
                    "predicates", predicateCount, "candidates", candidates.size()));
            return Optional.of(candidates);
        } catch (Exception e) {
            // No usable index, query service down, or any other issue: fall back to the scan.
            log.warn(StructuredLog.event(
                    "repository_query_pushdown_unavailable", "region", region,
                    "predicates", predicateCount, "error", e.getMessage()));
            return Optional.empty();
        }
    }

    /**
     * Numeric-comparison fragment over one or more candidate scalar paths (OR-ed): {@code TO_NUMBER(p)
     * <op> $n} per path, each with a {@code TYPE(p) = "string"} escape so a number stored as a string is
     * kept as a candidate (the matcher re-filters). A non-numeric scalar can never be a true numeric
     * match (OQL parses numeric fields with {@code Double.parseDouble} too), so this is a superset.
     */
    private static String numericFragment(String op, String nParam, String... paths) {
        StringBuilder fragment = new StringBuilder("(");
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) {
                fragment.append(" OR ");
            }
            fragment.append("TO_NUMBER(").append(paths[i]).append(") ").append(op).append(" $").append(nParam)
                    .append(" OR TYPE(").append(paths[i]).append(") = \"string\"");
        }
        return fragment.append(")").toString();
    }

    /**
     * String-equality fragment over one or more candidate scalar paths (OR-ed): {@code TO_STRING(p) = $v}
     * per path (matches regardless of the scalar's JSON type), plus an optional numeric branch when the
     * literal looks numeric (covers whole-number doubles where TO_STRING and String.valueOf differ).
     */
    private static String stringFragment(String vParam, String nParam, String... paths) {
        StringBuilder fragment = new StringBuilder("(");
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) {
                fragment.append(" OR ");
            }
            fragment.append("TO_STRING(").append(paths[i]).append(") = $").append(vParam);
            if (nParam != null) {
                fragment.append(" OR ").append(paths[i]).append(" = $").append(nParam);
            }
        }
        return fragment.append(")").toString();
    }

    /** Parse a literal's numeric value, or null when it is not numeric. */
    private static Double parseNumericOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Verify which keys still have a live value document (concurrently) and prune the expired ones
     * from the region's keyset metadata. Best-effort: on any failure the original keyset is returned
     * unpruned. Invalidated entries still exist (value-less marker), so they are not pruned.
     */
    private List<String> pruneExpiredKeys(String region, List<String> keys) {
        try {
            List<String> ordered = new ArrayList<>(keys);
            List<CompletableFuture<ExistsResult>> futures = new ArrayList<>(ordered.size());
            for (String key : ordered) {
                futures.add(collection.async().exists(DocumentKeyUtil.docId(region, key)));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<String> live = new ArrayList<>(ordered.size());
            List<String> stale = new ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) {
                if (futures.get(i).join().exists()) {
                    live.add(ordered.get(i));
                } else {
                    stale.add(ordered.get(i));
                }
            }

            if (!stale.isEmpty()) {
                mutateKeySetMetadata(region, current -> current.removeAll(stale), "evict:" + stale.size());
                log.info(StructuredLog.event(
                        "repository_keyset_evicted", "region", region, "evicted", stale.size()));
            }
            return live;
        } catch (Exception e) {
            log.warn(StructuredLog.event(
                    "repository_keyset_prune_failed", "region", region, "error", e.getMessage()));
            return keys;
        }
    }


    private void updateKeySetMetadataForDocId(String docId, boolean add) {
        if (docId == null || docId.startsWith(KEYSET_META_PREFIX)) {
            return;
        }

        ParsedDocumentKey parsed = parseDocumentKey(docId);

        if (parsed == null) {
            log.warn(StructuredLog.event(
                    "repository_key_set_metadata_skip_unparseable_doc_id",
                    "docId", docId,
                    "add", add
            ));
            return;
        }

        updateKeySetMetadata(parsed.region(), parsed.key(), add);
    }

    private void updateKeySetMetadata(String region, String key, boolean add) {
        if (add) {
            // Contention-free add: a server-side sub-document arrayAddUnique applies atomically without
            // a read-modify-write CAS, so concurrent single-key puts (the hot path) can never lose a
            // keyset update by exhausting the CAS retry budget. Removes have no sub-document
            // by-value equivalent, so they stay on the CAS path below.
            addKeyToKeySetMetadata(region, key);
            return;
        }
        mutateKeySetMetadata(region, current -> current.remove(key), "remove:" + key);
    }

    /**
     * Add {@code key} to the region's keyset metadata via an atomic sub-document {@code arrayAddUnique}
     * (creating the document/array if absent), which the server applies without CAS — so concurrent
     * adds do not conflict and none is lost. {@code keySet}/{@code size} read the {@code keys} array, so
     * the informational {@code length} field is intentionally not maintained here (the CAS remove path
     * rewrites it). Falls back to the CAS read-modify-write on any unexpected sub-document failure.
     */
    private void addKeyToKeySetMetadata(String region, String key) {
        String metadataDocId = keySetMetadataDocId(region);
        try {
            collection.mutateIn(metadataDocId,
                    java.util.List.of(
                            MutateInSpec.arrayAddUnique(FIELD_KEYS, key).createPath(),
                            MutateInSpec.upsert(FIELD_TYPE, "keySetMetadata"),
                            MutateInSpec.upsert("region", region)),
                    mutateInOptions());
        } catch (PathExistsException alreadyPresent) {
            // The key is already in the set (e.g. re-putting an existing key) — nothing to do.
        } catch (RuntimeException e) {
            log.warn(StructuredLog.event(
                    "repository_key_set_subdoc_add_fallback",
                    "region", region, "key", key, "error", e.getMessage()));
            mutateKeySetMetadata(region, current -> current.add(key), "add:" + key);
        }
    }

    private MutateInOptions mutateInOptions() {
        MutateInOptions options = MutateInOptions.mutateInOptions().storeSemantics(StoreSemantics.UPSERT);
        if (writeDurability != DurabilityLevel.NONE) {
            options.durability(writeDurability);
        }
        return options;
    }

    /**
     * Apply a mutation to a region's keyset-metadata document atomically, using compare-and-swap
     * with bounded retries.
     *
     * <p>The keyset metadata is a single per-region document maintained by read-modify-write. A
     * plain read-then-upsert would lose updates under concurrent writers (multiple in-flight
     * operations, or multiple shim replicas sharing one Couchbase). Instead we read the current
     * document (capturing its CAS), apply the mutation, and {@code insert} (when absent) or
     * {@code replace} with the CAS. A concurrent change invalidates the CAS (or the insert finds the
     * document already created), so we re-read and re-apply. This keeps {@code size}/{@code keySet}
     * correct under concurrency. It remains best-effort: if all attempts conflict, we log and move on.
     */
    private void mutateKeySetMetadata(String region, Consumer<TreeSet<String>> mutation, String description) {
        String metadataDocId = keySetMetadataDocId(region);
        int maxAttempts = CAS_MAX_ATTEMPTS;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                TreeSet<String> keys = new TreeSet<>();
                Long cas = null;

                try {
                    GetResult existing = collection.get(metadataDocId);
                    cas = existing.cas();
                    JsonArray existingKeys = existing.contentAsObject().getArray(FIELD_KEYS);
                    if (existingKeys != null) {
                        for (Object rawKey : existingKeys) {
                            if (rawKey != null) {
                                keys.add(String.valueOf(rawKey));
                            }
                        }
                    }
                } catch (DocumentNotFoundException notFound) {
                    cas = null; // document does not exist yet; we will insert it
                }

                mutation.accept(keys);

                JsonArray keyArray = JsonArray.create();
                for (String currentKey : keys) {
                    keyArray.add(currentKey);
                }
                JsonObject metadata = JsonObject.create()
                        .put(FIELD_TYPE, "keySetMetadata")
                        .put("region", region)
                        .put(FIELD_KEYS, keyArray)
                        .put(FIELD_LENGTH, keys.size());

                if (cas == null) {
                    // Create only if still absent; a concurrent create makes this fail and retry.
                    collection.insert(metadataDocId, metadata);
                } else {
                    // Replace only if unchanged since our read; a concurrent change fails and retries.
                    collection.replace(metadataDocId, metadata,
                            ReplaceOptions.replaceOptions().cas(cas));
                }

                log.info(StructuredLog.event(
                        "repository_key_set_metadata_updated",
                        "region", region,
                        "metadataDocId", metadataDocId,
                        "mutation", description,
                        "count", keys.size(),
                        "attempt", attempt
                ));
                return;
            } catch (CasMismatchException | DocumentExistsException conflict) {
                // A concurrent writer changed the document; re-read and re-apply after a short
                // randomized backoff so colliding writers don't keep retrying in lockstep (which is
                // what let a writer exhaust its retry budget and silently drop a keyset update).
                if (attempt == maxAttempts) {
                    log.warn(StructuredLog.event(
                            "repository_key_set_metadata_cas_exhausted",
                            "region", region,
                            "metadataDocId", metadataDocId,
                            "mutation", description,
                            "attempts", maxAttempts
                    ));
                    return;
                }
                backoffBeforeRetry(attempt);
            } catch (Exception e) {
                // Keyset metadata is a best-effort secondary index; never fail the primary op for it.
                log.warn(StructuredLog.event(
                        "repository_key_set_metadata_update_error",
                        "region", region,
                        "metadataDocId", metadataDocId,
                        "mutation", description,
                        "error", e.getMessage()
                ), e);
                return;
            }
        }
    }

    /** Capped, jittered backoff between CAS retries to break up lockstep contention on the keyset doc. */
    private static void backoffBeforeRetry(int attempt) {
        long capMillis = Math.min(50L, (1L << Math.min(attempt, 6)));   // 2,4,8,16,32,50,50...
        long sleepMillis = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, capMillis + 1);
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String keySetMetadataDocId(String region) {
        return KEYSET_META_PREFIX + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(region.getBytes(StandardCharsets.UTF_8));
    }

    // --- Durable-subscription persistence primitive (1.2.0-M1) -------------------------------------
    // All five methods short-circuit to the no-op interface behavior unless DURABLE_PERSISTENCE is on,
    // so the single-instance path is byte-for-byte unchanged by default.

    /** Enable the durable-persistence primitive with the given queue bound. Package-private test seam. */
    void enableDurablePersistenceForTesting(int maxQueue) {
        this.durablePersistenceEnabled = true;
        this.durableMaxQueue = maxQueue;
    }

    private static String durableDocId(String durableId) {
        return DURABLE_META_PREFIX + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(durableId.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Persist the durable client's registry record (interests/CQs/timeout/away) via sub-document upserts
     * so an existing event queue on the same doc is left untouched; the doc is created if absent.
     */
    @Override
    public void saveDurable(DurableRecord record) {
        if (!durablePersistenceEnabled || record == null || record.durableId() == null) {
            return;
        }
        String docId = durableDocId(record.durableId());
        JsonObject encoded = encodeDurableRecord(record);
        try {
            collection.mutateIn(docId, java.util.List.of(
                    MutateInSpec.upsert(FIELD_TYPE, TYPE_DURABLE_REGISTRY),
                    MutateInSpec.upsert(FIELD_DURABLE_ID, record.durableId()),
                    MutateInSpec.upsert(FIELD_TIMEOUT_SECONDS, record.timeoutSeconds()),
                    MutateInSpec.upsert(FIELD_AWAY, record.away()),
                    MutateInSpec.upsert(FIELD_INTERESTS, encoded.getArray(FIELD_INTERESTS)),
                    MutateInSpec.upsert(FIELD_CQS, encoded.getArray(FIELD_CQS))),
                    mutateInOptions());
            log.info(StructuredLog.event("repository_durable_saved",
                    "durableId", record.durableId(), "away", record.away(),
                    "interests", record.interests().size(), "cqs", record.cqs().size()));
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_durable_save_error", "durableId", record.durableId(),
                    "error", e.getMessage()), e);
            throw new RepositoryException("saveDurable failed for durableId=" + record.durableId(), e);
        }
    }

    @Override
    public Optional<DurableRecord> loadDurable(String durableId) {
        if (!durablePersistenceEnabled || durableId == null) {
            return Optional.empty();
        }
        try {
            JsonObject content = collection.get(durableDocId(durableId)).contentAsObject();
            log.info(StructuredLog.event("repository_durable_loaded", "durableId", durableId));
            return Optional.of(decodeDurableRecord(content));
        } catch (DocumentNotFoundException e) {
            log.info(StructuredLog.event("repository_durable_load_miss", "durableId", durableId));
            return Optional.empty();
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_durable_load_error", "durableId", durableId, "error", e.getMessage()), e);
            throw new RepositoryException("loadDurable failed for durableId=" + durableId, e);
        }
    }

    /**
     * Append one event frame to the durable client's persisted queue with an atomic sub-document
     * {@code arrayAppend} (contention-free, no read-modify-write CAS), creating the doc/array if absent
     * and stamping it identifiable. Bounds the queue at {@code DURABLE_MAX_QUEUE} via a best-effort trim
     * of the oldest entries when it overflows.
     */
    @Override
    public void enqueueDurableEvent(String durableId, byte[] event) {
        if (!durablePersistenceEnabled || durableId == null || event == null) {
            return;
        }
        String docId = durableDocId(durableId);
        String encoded = Base64.getEncoder().encodeToString(event);
        try {
            collection.mutateIn(docId, java.util.List.of(
                    MutateInSpec.arrayAppend(FIELD_QUEUE, java.util.List.of(encoded)).createPath(),
                    MutateInSpec.upsert(FIELD_TYPE, TYPE_DURABLE_REGISTRY),
                    MutateInSpec.upsert(FIELD_DURABLE_ID, durableId)),
                    mutateInOptions());
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_durable_enqueue_error", "durableId", durableId, "error", e.getMessage()), e);
            throw new RepositoryException("enqueueDurableEvent failed for durableId=" + durableId, e);
        }
        if (durableMaxQueue > 0) {
            trimDurableQueueIfNeeded(docId, durableId);
        }
    }

    /**
     * Drop the oldest entries when a durable queue exceeds the bound, keeping the newest
     * {@code durableMaxQueue}. Best-effort and CAS-guarded so a concurrent enqueue is never lost; on any
     * failure the queue is left as-is (the hard 20 MiB document ceiling still applies).
     */
    private void trimDurableQueueIfNeeded(String docId, String durableId) {
        try {
            LookupInResult counted = collection.lookupIn(
                    docId, java.util.List.of(LookupInSpec.count(FIELD_QUEUE)));
            int size = counted.contentAs(0, Integer.class);
            if (size <= durableMaxQueue) {
                return;
            }
            for (int attempt = 1; attempt <= CAS_MAX_ATTEMPTS; attempt++) {
                GetResult existing = collection.get(docId);
                JsonArray queue = existing.contentAsObject().getArray(FIELD_QUEUE);
                if (queue == null || queue.size() <= durableMaxQueue) {
                    return;
                }
                int drop = queue.size() - durableMaxQueue;
                JsonArray trimmed = JsonArray.create();
                for (int i = drop; i < queue.size(); i++) {
                    trimmed.add(queue.get(i));
                }
                try {
                    collection.mutateIn(docId,
                            java.util.List.of(MutateInSpec.upsert(FIELD_QUEUE, trimmed)),
                            durableCasOptions(existing.cas()));
                    log.info(StructuredLog.event("repository_durable_queue_trimmed",
                            "durableId", durableId, "dropped", drop, "kept", durableMaxQueue));
                    return;
                } catch (CasMismatchException retry) {
                    // a concurrent enqueue grew the queue; re-read and re-trim
                }
            }
        } catch (Exception e) {
            log.warn(StructuredLog.event(
                    "repository_durable_queue_trim_failed", "durableId", durableId, "error", e.getMessage()));
        }
    }

    @Override
    public java.util.List<byte[]> drainDurableQueue(String durableId) {
        if (!durablePersistenceEnabled || durableId == null) {
            return java.util.List.of();
        }
        String docId = durableDocId(durableId);
        for (int attempt = 1; attempt <= CAS_MAX_ATTEMPTS; attempt++) {
            try {
                GetResult existing;
                try {
                    existing = collection.get(docId);
                } catch (DocumentNotFoundException none) {
                    return java.util.List.of();
                }
                JsonArray queue = existing.contentAsObject().getArray(FIELD_QUEUE);
                if (queue == null || queue.isEmpty()) {
                    return java.util.List.of();
                }
                java.util.List<byte[]> events = new ArrayList<>(queue.size());
                for (Object raw : queue) {
                    if (raw != null) {
                        events.add(Base64.getDecoder().decode(String.valueOf(raw)));
                    }
                }
                // Clear only if unchanged since our read, so an event enqueued concurrently is retained
                // (the CAS fails and we re-read), never silently dropped.
                collection.mutateIn(docId,
                        java.util.List.of(MutateInSpec.upsert(FIELD_QUEUE, JsonArray.create())),
                        durableCasOptions(existing.cas()));
                log.info(StructuredLog.event(
                        "repository_durable_drained", "durableId", durableId, "events", events.size()));
                return events;
            } catch (CasMismatchException conflict) {
                // a concurrent enqueue changed the doc; re-read and retry
            } catch (Exception e) {
                log.error(StructuredLog.event(
                        "repository_durable_drain_error", "durableId", durableId, "error", e.getMessage()), e);
                throw new RepositoryException("drainDurableQueue failed for durableId=" + durableId, e);
            }
        }
        log.warn(StructuredLog.event("repository_durable_drain_cas_exhausted", "durableId", durableId));
        return java.util.List.of();
    }

    @Override
    public void dropDurable(String durableId) {
        if (!durablePersistenceEnabled || durableId == null) {
            return;
        }
        try {
            collection.remove(durableDocId(durableId), removeOptions());
            log.info(StructuredLog.event("repository_durable_dropped", "durableId", durableId));
        } catch (DocumentNotFoundException alreadyGone) {
            log.info(StructuredLog.event("repository_durable_drop_miss", "durableId", durableId));
        } catch (Exception e) {
            log.error(StructuredLog.event(
                    "repository_durable_drop_error", "durableId", durableId, "error", e.getMessage()), e);
            throw new RepositoryException("dropDurable failed for durableId=" + durableId, e);
        }
    }

    private MutateInOptions durableCasOptions(long cas) {
        MutateInOptions options = MutateInOptions.mutateInOptions().cas(cas);
        if (writeDurability != DurabilityLevel.NONE) {
            options.durability(writeDurability);
        }
        return options;
    }

    /**
     * Encode a {@link DurableRecord}'s metadata (no queue) to its JSON document form. Package-private
     * so a unit test can round-trip the codec without a live Couchbase (pairs with
     * {@link #decodeDurableRecord}).
     */
    static JsonObject encodeDurableRecord(DurableRecord record) {
        JsonObject body = JsonObject.create()
                .put(FIELD_TYPE, TYPE_DURABLE_REGISTRY)
                .put(FIELD_DURABLE_ID, record.durableId())
                .put(FIELD_TIMEOUT_SECONDS, record.timeoutSeconds())
                .put(FIELD_AWAY, record.away());

        JsonArray interests = JsonArray.create();
        for (DurableRecord.InterestSpec spec : record.interests()) {
            JsonObject entry = JsonObject.create()
                    .put(FIELD_REGION, spec.region())
                    .put(FIELD_KIND, spec.kind().name());
            if (spec.kind() == DurableRecord.InterestSpec.Kind.KEYS) {
                JsonArray keys = JsonArray.create();
                for (String key : spec.keys()) {
                    keys.add(key);
                }
                entry.put(FIELD_KEYS, keys);
            } else if (spec.kind() == DurableRecord.InterestSpec.Kind.REGEX) {
                entry.put(FIELD_REGEX, spec.regex());
            }
            interests.add(entry);
        }
        body.put(FIELD_INTERESTS, interests);

        JsonArray cqs = JsonArray.create();
        for (DurableRecord.CqSpec cq : record.cqs()) {
            cqs.add(JsonObject.create()
                    .put(FIELD_CQ_NAME, cq.cqName())
                    .put(FIELD_REGION, cq.region())
                    .put(FIELD_QUERY, cq.query()));
        }
        body.put(FIELD_CQS, cqs);
        return body;
    }

    /** Decode a durable doc's metadata (queue ignored) back into a {@link DurableRecord}. */
    static DurableRecord decodeDurableRecord(JsonObject body) {
        String durableId = body.getString(FIELD_DURABLE_ID);
        Integer timeout = body.getInt(FIELD_TIMEOUT_SECONDS);
        boolean away = Boolean.TRUE.equals(body.getBoolean(FIELD_AWAY));

        java.util.List<DurableRecord.InterestSpec> interests = new ArrayList<>();
        JsonArray interestArray = body.getArray(FIELD_INTERESTS);
        if (interestArray != null) {
            for (Object raw : interestArray) {
                if (!(raw instanceof JsonObject entry)) {
                    continue;
                }
                String region = entry.getString(FIELD_REGION);
                DurableRecord.InterestSpec.Kind kind =
                        DurableRecord.InterestSpec.Kind.valueOf(entry.getString(FIELD_KIND));
                switch (kind) {
                    case KEYS -> {
                        java.util.List<String> keys = new ArrayList<>();
                        JsonArray keyArray = entry.getArray(FIELD_KEYS);
                        if (keyArray != null) {
                            for (Object key : keyArray) {
                                if (key != null) {
                                    keys.add(String.valueOf(key));
                                }
                            }
                        }
                        interests.add(DurableRecord.InterestSpec.keys(region, keys));
                    }
                    case REGEX -> interests.add(
                            DurableRecord.InterestSpec.regex(region, entry.getString(FIELD_REGEX)));
                    default -> interests.add(DurableRecord.InterestSpec.allKeys(region));
                }
            }
        }

        java.util.List<DurableRecord.CqSpec> cqs = new ArrayList<>();
        JsonArray cqArray = body.getArray(FIELD_CQS);
        if (cqArray != null) {
            for (Object raw : cqArray) {
                if (raw instanceof JsonObject entry) {
                    cqs.add(new DurableRecord.CqSpec(
                            entry.getString(FIELD_CQ_NAME),
                            entry.getString(FIELD_REGION),
                            entry.getString(FIELD_QUERY)));
                }
            }
        }

        return new DurableRecord(durableId, timeout == null ? 0 : timeout, away, interests, cqs);
    }

    private static ParsedDocumentKey parseDocumentKey(String docId) {
        int separator = docId.indexOf("::");

        if (separator <= 0 || separator + 2 >= docId.length()) {
            return null;
        }

        String region = docId.substring(0, separator);
        String key = docId.substring(separator + 2);

        if (region.isBlank() || key.isBlank()) {
            return null;
        }

        return new ParsedDocumentKey(region, key);
    }

    private record ParsedDocumentKey(String region, String key) {
    }

    // Package-private (not private) so the JSON round-trip property test can drive the persistence
    // codec directly without a live Couchbase. Pairs with decodeStoredValue.
    static JsonObject encodeStoredValue(StoredValue value) {
        JsonObject body = JsonObject.create();

        if (value.type() == StoredValue.Type.BOOLEAN) {
            body.put(FIELD_TYPE, TYPE_BOOLEAN);
            body.put(FIELD_VALUE, value.asBoolean());
            return body;
        }

        if (value.type() == StoredValue.Type.CHARACTER) {
            body.put(FIELD_TYPE, TYPE_CHARACTER);
            body.put(FIELD_VALUE, String.valueOf(value.asCharacter()));
            return body;
        }

        if (value.type() == StoredValue.Type.BYTE) {
            body.put(FIELD_TYPE, TYPE_BYTE);
            body.put(FIELD_VALUE, value.asByte());
            return body;
        }

        if (value.type() == StoredValue.Type.BYTE_ARRAY) {
            byte[] byteArray = value.asByteArray();

            body.put(FIELD_TYPE, TYPE_BYTE_ARRAY);
            body.put(FIELD_VALUE_BASE64, Base64.getEncoder().encodeToString(byteArray));
            body.put(FIELD_LENGTH, byteArray.length);
            return body;
        }

        if (value.type() == StoredValue.Type.BOOLEAN_ARRAY) {
            boolean[] booleanArray = value.asBooleanArray();
            JsonArray jsonArray = JsonArray.create();

            for (boolean item : booleanArray) {
                jsonArray.add(item);
            }

            body.put(FIELD_TYPE, TYPE_BOOLEAN_ARRAY);
            body.put(FIELD_VALUE, jsonArray);
            body.put(FIELD_LENGTH, booleanArray.length);
            return body;
        }

        if (value.type() == StoredValue.Type.CHAR_ARRAY) {
            char[] charArray = value.asCharArray();
            JsonArray jsonArray = JsonArray.create();

            for (char item : charArray) {
                jsonArray.add(String.valueOf(item));
            }

            body.put(FIELD_TYPE, TYPE_CHAR_ARRAY);
            body.put(FIELD_VALUE, jsonArray);
            body.put(FIELD_LENGTH, charArray.length);
            return body;
        }

        if (value.type() == StoredValue.Type.SHORT_ARRAY) {
            short[] shortArray = value.asShortArray();
            JsonArray jsonArray = JsonArray.create();

            for (short item : shortArray) {
                jsonArray.add(item);
            }

            body.put(FIELD_TYPE, TYPE_SHORT_ARRAY);
            body.put(FIELD_VALUE, jsonArray);
            body.put(FIELD_LENGTH, shortArray.length);
            return body;
        }

        if (value.type() == StoredValue.Type.INT_ARRAY) {
            int[] intArray = value.asIntArray();
            JsonArray jsonArray = JsonArray.create();

            for (int item : intArray) {
                jsonArray.add(item);
            }

            body.put(FIELD_TYPE, TYPE_INT_ARRAY);
            body.put(FIELD_VALUE, jsonArray);
            body.put(FIELD_LENGTH, intArray.length);
            return body;
        }

        if (value.type() == StoredValue.Type.LONG_ARRAY) {
            long[] longArray = value.asLongArray();
            JsonArray jsonArray = JsonArray.create();

            for (long item : longArray) {
                jsonArray.add(item);
            }

            body.put(FIELD_TYPE, TYPE_LONG_ARRAY);
            body.put(FIELD_VALUE, jsonArray);
            body.put(FIELD_LENGTH, longArray.length);
            return body;
        }

        if (value.type() == StoredValue.Type.FLOAT_ARRAY) {
            float[] floatArray = value.asFloatArray();
            JsonArray jsonArray = JsonArray.create();

            for (float item : floatArray) {
                jsonArray.add(item);
            }

            body.put(FIELD_TYPE, TYPE_FLOAT_ARRAY);
            body.put(FIELD_VALUE, jsonArray);
            body.put(FIELD_LENGTH, floatArray.length);
            return body;
        }

        if (value.type() == StoredValue.Type.DOUBLE_ARRAY) {
            double[] doubleArray = value.asDoubleArray();
            JsonArray jsonArray = JsonArray.create();

            for (double item : doubleArray) {
                jsonArray.add(item);
            }

            body.put(FIELD_TYPE, TYPE_DOUBLE_ARRAY);
            body.put(FIELD_VALUE, jsonArray);
            body.put(FIELD_LENGTH, doubleArray.length);
            return body;
        }

        if (value.type() == StoredValue.Type.STRING_ARRAY) {
            String[] stringArray = value.asStringArray();
            JsonArray jsonArray = JsonArray.create();

            for (String item : stringArray) {
                jsonArray.add(item);
            }

            body.put(FIELD_TYPE, TYPE_STRING_ARRAY);
            body.put(FIELD_VALUE, jsonArray);
            body.put(FIELD_LENGTH, stringArray.length);
            return body;
        }

        if (value.type() == StoredValue.Type.STRING_ARRAY_LIST) {
            ArrayList<String> stringArrayList = value.asStringArrayList();
            JsonArray jsonArray = JsonArray.create();

            for (String item : stringArrayList) {
                jsonArray.add(item);
            }

            body.put(FIELD_TYPE, TYPE_STRING_ARRAY_LIST);
            body.put(FIELD_VALUE, jsonArray);
            body.put(FIELD_LENGTH, stringArrayList.size());
            return body;
        }

        if (value.type() == StoredValue.Type.STRING_HASH_MAP) {
            LinkedHashMap<String, String> stringHashMap = value.asStringHashMap();
            JsonObject jsonMap = JsonObject.create();

            for (Map.Entry<String, String> entry : stringHashMap.entrySet()) {
                /*
                 * JSON object field names cannot be null. A null Java map key is
                 * valid in HashMap/LinkedHashMap, but it cannot be represented
                 * safely as a normal JSON object field without inventing an
                 * escape protocol. For the supported Couchbase envelope we
                 * preserve String keys and null String values.
                 */
                if (entry.getKey() != null) {
                    jsonMap.put(entry.getKey(), entry.getValue());
                }
            }

            body.put(FIELD_TYPE, TYPE_STRING_HASH_MAP);
            body.put(FIELD_VALUE, jsonMap);
            body.put(FIELD_LENGTH, stringHashMap.size());
            return body;
        }

        if (value.type() == StoredValue.Type.STRING_OBJECT_HASH_MAP) {
            LinkedHashMap<String, Object> stringObjectHashMap = value.asStringObjectHashMap();
            JsonObject jsonMap = JsonObject.create();

            for (Map.Entry<String, Object> entry : stringObjectHashMap.entrySet()) {
                if (entry.getKey() != null) {
                    jsonMap.put(entry.getKey(), encodeMapObjectValue(entry.getValue()));
                }
            }

            body.put(FIELD_TYPE, TYPE_STRING_OBJECT_HASH_MAP);
            body.put(FIELD_VALUE, jsonMap);
            body.put(FIELD_LENGTH, stringObjectHashMap.size());
            return body;
        }

        if (value.type() == StoredValue.Type.JAVA_SERIALIZED_OBJECT) {
            byte[] serializedValue = value.asJavaSerializedValue();

            body.put(FIELD_TYPE, TYPE_JAVA_SERIALIZED_OBJECT);
            body.put("className", value.asJavaSerializedClassName());
            body.put(FIELD_VALUE_BASE64, Base64.getEncoder().encodeToString(serializedValue));
            body.put(FIELD_LENGTH, serializedValue.length);
            return body;
        }

        if (value.type() == StoredValue.Type.OBJECT_ARRAY) {
            byte[] encodedObjectArrayValue = value.asObjectArrayValue();

            body.put(FIELD_TYPE, TYPE_OBJECT_ARRAY);
            body.put(FIELD_VALUE_BASE64, Base64.getEncoder().encodeToString(encodedObjectArrayValue));
            body.put(FIELD_LENGTH, encodedObjectArrayValue.length);
            return body;
        }

        if (value.type() == StoredValue.Type.OBJECT_ARRAY_LIST) {
            byte[] encodedObjectArrayListValue = value.asObjectArrayListValue();

            body.put(FIELD_TYPE, TYPE_OBJECT_ARRAY_LIST);
            body.put(FIELD_VALUE_BASE64, Base64.getEncoder().encodeToString(encodedObjectArrayListValue));
            body.put(FIELD_LENGTH, encodedObjectArrayListValue.length);
            return body;
        }

        if (value.type() == StoredValue.Type.OPAQUE_GEODE_VALUE) {
            byte[] encodedOpaqueGeodeValue = value.asOpaqueGeodeValue();

            body.put(FIELD_TYPE, TYPE_OPAQUE_GEODE_VALUE);
            body.put(FIELD_OPAQUE_GEODE_TYPE_NAME, value.asOpaqueGeodeTypeName());
            body.put(FIELD_VALUE_BASE64, Base64.getEncoder().encodeToString(encodedOpaqueGeodeValue));
            body.put(FIELD_LENGTH, encodedOpaqueGeodeValue.length);
            return body;
        }

        if (value.type() == StoredValue.Type.PDX_INSTANCE) {
            byte[] encodedPdxInstanceValue = value.asPdxInstanceValue();

            body.put(FIELD_TYPE, TYPE_PDX_INSTANCE);
            body.put(FIELD_VALUE_BASE64, Base64.getEncoder().encodeToString(encodedPdxInstanceValue));
            body.put(FIELD_LENGTH, encodedPdxInstanceValue.length);
            return body;
        }

        if (value.type() == StoredValue.Type.SHORT) {
            body.put(FIELD_TYPE, TYPE_SHORT);
            body.put(FIELD_VALUE, value.asShort());
            return body;
        }

        if (value.type() == StoredValue.Type.INTEGER) {
            body.put(FIELD_TYPE, TYPE_INTEGER);
            body.put(FIELD_VALUE, value.asInteger());
            return body;
        }

        if (value.type() == StoredValue.Type.LONG) {
            body.put(FIELD_TYPE, TYPE_LONG);
            body.put(FIELD_VALUE, value.asLong());
            return body;
        }

        if (value.type() == StoredValue.Type.FLOAT) {
            body.put(FIELD_TYPE, TYPE_FLOAT);
            body.put(FIELD_VALUE, value.asFloat());
            return body;
        }

        if (value.type() == StoredValue.Type.DOUBLE) {
            body.put(FIELD_TYPE, TYPE_DOUBLE);
            body.put(FIELD_VALUE, value.asDouble());
            return body;
        }

        if (value.type() == StoredValue.Type.DATE) {
            Date date = value.asDate();
            body.put(FIELD_TYPE, TYPE_DATE);
            body.put(FIELD_VALUE, date.toInstant().toString());
            body.put(FIELD_EPOCH_MILLIS, date.getTime());
            return body;
        }

        body.put(FIELD_TYPE, TYPE_STRING);
        body.put(FIELD_VALUE, value.value());
        return body;
    }

    // Package-private (not private) for the JSON round-trip property test; pairs with encodeStoredValue.
    static StoredValue decodeStoredValue(JsonObject content) {
        if (content == null || !content.containsKey(FIELD_TYPE)) {
            if (content == null || !content.containsKey(FIELD_VALUE)) {
                return null;
            }
        }

        String type = content.containsKey(FIELD_TYPE)
                ? content.getString(FIELD_TYPE)
                : TYPE_STRING;

        Object rawValue = content.get(FIELD_VALUE);

        if (TYPE_BOOLEAN.equalsIgnoreCase(type)) {
            if (rawValue instanceof Boolean bool) {
                return StoredValue.booleanValue(bool);
            }

            if (rawValue instanceof String text) {
                if ("true".equalsIgnoreCase(text)) {
                    return StoredValue.booleanValue(Boolean.TRUE);
                }

                if ("false".equalsIgnoreCase(text)) {
                    return StoredValue.booleanValue(Boolean.FALSE);
                }
            }

            return null;
        }

        if (TYPE_CHARACTER.equalsIgnoreCase(type)) {
            if (rawValue instanceof Character character) {
                return StoredValue.characterValue(character);
            }

            if (rawValue instanceof String text) {
                if (text.length() == 1) {
                    return StoredValue.characterValue(text.charAt(0));
                }

                if (!text.isEmpty()) {
                    return StoredValue.stringValue(text);
                }
            }

            return null;
        }

        if (TYPE_BYTE.equalsIgnoreCase(type)) {
            if (rawValue instanceof Number number) {
                return StoredValue.byteValue(number.byteValue());
            }

            if (rawValue instanceof String text) {
                try {
                    return StoredValue.byteValue(Byte.valueOf(text));
                } catch (NumberFormatException e) {
                    return StoredValue.stringValue(text);
                }
            }

            return null;
        }

        if (TYPE_BYTE_ARRAY.equalsIgnoreCase(type)) {
            return decodeByteArrayStoredValue(content, rawValue);
        }

        if (TYPE_BOOLEAN_ARRAY.equalsIgnoreCase(type)) {
            return decodeBooleanArrayStoredValue(content, rawValue);
        }

        if (TYPE_CHAR_ARRAY.equalsIgnoreCase(type)) {
            return decodeCharArrayStoredValue(content, rawValue);
        }

        if (TYPE_SHORT_ARRAY.equalsIgnoreCase(type)) {
            return decodeShortArrayStoredValue(content, rawValue);
        }

        if (TYPE_INT_ARRAY.equalsIgnoreCase(type)) {
            return decodeIntArrayStoredValue(content, rawValue);
        }

        if (TYPE_LONG_ARRAY.equalsIgnoreCase(type)) {
            return decodeLongArrayStoredValue(content, rawValue);
        }

        if (TYPE_FLOAT_ARRAY.equalsIgnoreCase(type)) {
            return decodeFloatArrayStoredValue(content, rawValue);
        }

        if (TYPE_DOUBLE_ARRAY.equalsIgnoreCase(type)) {
            return decodeDoubleArrayStoredValue(content, rawValue);
        }

        if (TYPE_STRING_ARRAY.equalsIgnoreCase(type)) {
            return decodeStringArrayStoredValue(content, rawValue);
        }

        if (TYPE_STRING_ARRAY_LIST.equalsIgnoreCase(type)) {
            return decodeStringArrayListStoredValue(content, rawValue);
        }

        if (TYPE_STRING_HASH_MAP.equalsIgnoreCase(type)) {
            return decodeStringHashMapStoredValue(content, rawValue);
        }

        if (TYPE_STRING_OBJECT_HASH_MAP.equalsIgnoreCase(type)) {
            return decodeStringObjectHashMapStoredValue(content, rawValue);
        }

        if (TYPE_JAVA_SERIALIZED_OBJECT.equalsIgnoreCase(type)) {
            return decodeJavaSerializedObjectStoredValue(content);
        }

        if (TYPE_OBJECT_ARRAY.equalsIgnoreCase(type)) {
            return decodeObjectArrayStoredValue(content);
        }

        if (TYPE_OBJECT_ARRAY_LIST.equalsIgnoreCase(type)) {
            return decodeObjectArrayListStoredValue(content);
        }

        if (TYPE_OPAQUE_GEODE_VALUE.equalsIgnoreCase(type)) {
            return decodeOpaqueGeodeValueStoredValue(content);
        }

        if (TYPE_PDX_INSTANCE.equalsIgnoreCase(type)) {
            return decodePdxInstanceStoredValue(content);
        }

        if (TYPE_SHORT.equalsIgnoreCase(type)) {
            if (rawValue instanceof Number number) {
                return StoredValue.shortValue(number.shortValue());
            }

            if (rawValue instanceof String text) {
                try {
                    return StoredValue.shortValue(Short.valueOf(text));
                } catch (NumberFormatException e) {
                    return StoredValue.stringValue(text);
                }
            }

            return null;
        }

        if (TYPE_INTEGER.equalsIgnoreCase(type)) {
            if (rawValue instanceof Number number) {
                return StoredValue.integerValue(number.intValue());
            }

            if (rawValue instanceof String text) {
                try {
                    return StoredValue.integerValue(Integer.valueOf(text));
                } catch (NumberFormatException e) {
                    return StoredValue.stringValue(text);
                }
            }

            return null;
        }

        if (TYPE_LONG.equalsIgnoreCase(type)) {
            if (rawValue instanceof Number number) {
                return StoredValue.longValue(number.longValue());
            }

            if (rawValue instanceof String text) {
                try {
                    return StoredValue.longValue(Long.valueOf(text));
                } catch (NumberFormatException e) {
                    return StoredValue.stringValue(text);
                }
            }

            return null;
        }

        if (TYPE_FLOAT.equalsIgnoreCase(type)) {
            if (rawValue instanceof Number number) {
                return StoredValue.floatValue(number.floatValue());
            }

            if (rawValue instanceof String text) {
                try {
                    return StoredValue.floatValue(Float.valueOf(text));
                } catch (NumberFormatException e) {
                    return StoredValue.stringValue(text);
                }
            }

            return null;
        }

        if (TYPE_DOUBLE.equalsIgnoreCase(type)) {
            if (rawValue instanceof Number number) {
                return StoredValue.doubleValue(number.doubleValue());
            }

            if (rawValue instanceof String text) {
                try {
                    return StoredValue.doubleValue(Double.valueOf(text));
                } catch (NumberFormatException e) {
                    return StoredValue.stringValue(text);
                }
            }

            return null;
        }

        if (TYPE_DATE.equalsIgnoreCase(type)) {
            return decodeDateStoredValue(content, rawValue);
        }

        if (rawValue == null) {
            return null;
        }

        return StoredValue.stringValue(String.valueOf(rawValue));
    }

    private static StoredValue decodeByteArrayStoredValue(JsonObject content, Object rawValue) {
        Object rawBase64 = content.get(FIELD_VALUE_BASE64);

        if (rawBase64 instanceof String base64Text) {
            try {
                byte[] decoded = Base64.getDecoder().decode(base64Text);

                Object rawLength = content.get(FIELD_LENGTH);
                if (rawLength instanceof Number number && number.intValue() != decoded.length) {
                    log.warn(StructuredLog.event(
                            "repository_byte_array_length_mismatch",
                            "expectedLength", number.intValue(),
                            "actualLength", decoded.length
                    ));
                }

                return StoredValue.byteArrayValue(decoded);
            } catch (IllegalArgumentException e) {
                log.warn(StructuredLog.event(
                        "repository_byte_array_decode_failed",
                        "error", e.getMessage()
                ));
                return null;
            }
        }

        /*
         * Compatibility fallback if a hand-written document used "value" instead
         * of "valueBase64" for the Base64 payload.
         */
        if (rawValue instanceof String text) {
            try {
                return StoredValue.byteArrayValue(Base64.getDecoder().decode(text));
            } catch (IllegalArgumentException e) {
                return StoredValue.stringValue(text);
            }
        }

        return null;
    }


    private static StoredValue decodeBooleanArrayStoredValue(JsonObject content, Object rawValue) {
        List<?> rawList = rawListFromValue(rawValue);

        if (rawList == null) {
            return null;
        }

        boolean[] decoded = new boolean[rawList.size()];

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);

            if (item instanceof Boolean bool) {
                decoded[i] = bool;
            } else if (item instanceof String text) {
                if ("true".equalsIgnoreCase(text)) {
                    decoded[i] = true;
                } else if ("false".equalsIgnoreCase(text)) {
                    decoded[i] = false;
                } else {
                    log.warn(StructuredLog.event(
                            "repository_boolean_array_decode_failed",
                            "reason", "non_boolean_string_item",
                            "index", i,
                            "value", text
                    ));
                    return null;
                }
            } else {
                log.warn(StructuredLog.event(
                        "repository_boolean_array_decode_failed",
                        "reason", "unsupported_item_type",
                        "index", i,
                        "itemType", item == null ? "null" : item.getClass().getName()
                ));
                return null;
            }
        }

        warnLengthMismatch(content, "repository_boolean_array_length_mismatch", decoded.length);
        return StoredValue.booleanArrayValue(decoded);
    }

    private static StoredValue decodeCharArrayStoredValue(JsonObject content, Object rawValue) {
        List<?> rawList = rawListFromValue(rawValue);

        if (rawList == null) {
            return null;
        }

        char[] decoded = new char[rawList.size()];

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);

            if (item instanceof String text && text.length() == 1) {
                decoded[i] = text.charAt(0);
            } else if (item instanceof Character character) {
                decoded[i] = character;
            } else {
                log.warn(StructuredLog.event(
                        "repository_char_array_decode_failed",
                        "reason", "unsupported_item_type_or_length",
                        "index", i,
                        "itemType", item == null ? "null" : item.getClass().getName(),
                        "value", item == null ? "null" : String.valueOf(item)
                ));
                return null;
            }
        }

        warnLengthMismatch(content, "repository_char_array_length_mismatch", decoded.length);
        return StoredValue.charArrayValue(decoded);
    }

    private static StoredValue decodeShortArrayStoredValue(JsonObject content, Object rawValue) {
        List<?> rawList = rawListFromValue(rawValue);

        if (rawList == null) {
            return null;
        }

        short[] decoded = new short[rawList.size()];

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);

            if (item instanceof Number number) {
                decoded[i] = number.shortValue();
            } else if (item instanceof String text) {
                try {
                    decoded[i] = Short.parseShort(text);
                } catch (NumberFormatException e) {
                    log.warn(StructuredLog.event(
                            "repository_short_array_decode_failed",
                            "reason", "non_short_string_item",
                            "index", i,
                            "value", text
                    ));
                    return null;
                }
            } else {
                log.warn(StructuredLog.event(
                        "repository_short_array_decode_failed",
                        "reason", "unsupported_item_type",
                        "index", i,
                        "itemType", item == null ? "null" : item.getClass().getName()
                ));
                return null;
            }
        }

        warnLengthMismatch(content, "repository_short_array_length_mismatch", decoded.length);
        return StoredValue.shortArrayValue(decoded);
    }

    private static StoredValue decodeIntArrayStoredValue(JsonObject content, Object rawValue) {
        List<?> rawList = null;

        if (rawValue instanceof JsonArray jsonArray) {
            rawList = jsonArray.toList();
        } else if (rawValue instanceof List<?> list) {
            rawList = list;
        }

        if (rawList == null) {
            return null;
        }

        int[] decoded = new int[rawList.size()];

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);

            if (item instanceof Number number) {
                decoded[i] = number.intValue();
            } else if (item instanceof String text) {
                try {
                    decoded[i] = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    log.warn(StructuredLog.event(
                            "repository_int_array_decode_failed",
                            "reason", "non_integer_string_item",
                            "index", i,
                            "value", text
                    ));
                    return null;
                }
            } else {
                log.warn(StructuredLog.event(
                        "repository_int_array_decode_failed",
                        "reason", "unsupported_item_type",
                        "index", i,
                        "itemType", item == null ? "null" : item.getClass().getName()
                ));
                return null;
            }
        }

        Object rawLength = content.get(FIELD_LENGTH);
        if (rawLength instanceof Number number && number.intValue() != decoded.length) {
            log.warn(StructuredLog.event(
                    "repository_int_array_length_mismatch",
                    "expectedLength", number.intValue(),
                    "actualLength", decoded.length
            ));
        }

        return StoredValue.intArrayValue(decoded);
    }

    private static StoredValue decodeLongArrayStoredValue(JsonObject content, Object rawValue) {
        List<?> rawList = rawListFromValue(rawValue);

        if (rawList == null) {
            return null;
        }

        long[] decoded = new long[rawList.size()];

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);

            if (item instanceof Number number) {
                decoded[i] = number.longValue();
            } else if (item instanceof String text) {
                try {
                    decoded[i] = Long.parseLong(text);
                } catch (NumberFormatException e) {
                    log.warn(StructuredLog.event(
                            "repository_long_array_decode_failed",
                            "reason", "non_long_string_item",
                            "index", i,
                            "value", text
                    ));
                    return null;
                }
            } else {
                log.warn(StructuredLog.event(
                        "repository_long_array_decode_failed",
                        "reason", "unsupported_item_type",
                        "index", i,
                        "itemType", item == null ? "null" : item.getClass().getName()
                ));
                return null;
            }
        }

        warnLengthMismatch(content, "repository_long_array_length_mismatch", decoded.length);
        return StoredValue.longArrayValue(decoded);
    }

    private static StoredValue decodeFloatArrayStoredValue(JsonObject content, Object rawValue) {
        List<?> rawList = rawListFromValue(rawValue);

        if (rawList == null) {
            return null;
        }

        float[] decoded = new float[rawList.size()];

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);

            if (item instanceof Number number) {
                decoded[i] = number.floatValue();
            } else if (item instanceof String text) {
                try {
                    decoded[i] = Float.parseFloat(text);
                } catch (NumberFormatException e) {
                    log.warn(StructuredLog.event(
                            "repository_float_array_decode_failed",
                            "reason", "non_float_string_item",
                            "index", i,
                            "value", text
                    ));
                    return null;
                }
            } else {
                log.warn(StructuredLog.event(
                        "repository_float_array_decode_failed",
                        "reason", "unsupported_item_type",
                        "index", i,
                        "itemType", item == null ? "null" : item.getClass().getName()
                ));
                return null;
            }
        }

        warnLengthMismatch(content, "repository_float_array_length_mismatch", decoded.length);
        return StoredValue.floatArrayValue(decoded);
    }

    private static StoredValue decodeDoubleArrayStoredValue(JsonObject content, Object rawValue) {
        List<?> rawList = rawListFromValue(rawValue);

        if (rawList == null) {
            return null;
        }

        double[] decoded = new double[rawList.size()];

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);

            if (item instanceof Number number) {
                decoded[i] = number.doubleValue();
            } else if (item instanceof String text) {
                try {
                    decoded[i] = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    log.warn(StructuredLog.event(
                            "repository_double_array_decode_failed",
                            "reason", "non_double_string_item",
                            "index", i,
                            "value", text
                    ));
                    return null;
                }
            } else {
                log.warn(StructuredLog.event(
                        "repository_double_array_decode_failed",
                        "reason", "unsupported_item_type",
                        "index", i,
                        "itemType", item == null ? "null" : item.getClass().getName()
                ));
                return null;
            }
        }

        warnLengthMismatch(content, "repository_double_array_length_mismatch", decoded.length);
        return StoredValue.doubleArrayValue(decoded);
    }

    private static List<?> rawListFromValue(Object rawValue) {
        if (rawValue instanceof JsonArray jsonArray) {
            return jsonArray.toList();
        }

        if (rawValue instanceof List<?> list) {
            return list;
        }

        return null;
    }

    private static void warnLengthMismatch(JsonObject content, String eventName, int actualLength) {
        Object rawLength = content.get(FIELD_LENGTH);

        if (rawLength instanceof Number number && number.intValue() != actualLength) {
            log.warn(StructuredLog.event(
                    eventName,
                    "expectedLength", number.intValue(),
                    "actualLength", actualLength
            ));
        }
    }



    private static StoredValue decodeStringArrayStoredValue(JsonObject content, Object rawValue) {
        List<?> rawList = null;

        if (rawValue instanceof JsonArray jsonArray) {
            rawList = jsonArray.toList();
        } else if (rawValue instanceof List<?> list) {
            rawList = list;
        }

        if (rawList == null) {
            return null;
        }

        String[] decoded = new String[rawList.size()];

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);

            if (item == null) {
                decoded[i] = null;
            } else if (item instanceof String text) {
                decoded[i] = text;
            } else {
                decoded[i] = String.valueOf(item);
            }
        }

        Object rawLength = content.get(FIELD_LENGTH);
        if (rawLength instanceof Number number && number.intValue() != decoded.length) {
            log.warn(StructuredLog.event(
                    "repository_string_array_length_mismatch",
                    "expectedLength", number.intValue(),
                    "actualLength", decoded.length
            ));
        }

        return StoredValue.stringArrayValue(decoded);
    }

    private static StoredValue decodeStringArrayListStoredValue(JsonObject content, Object rawValue) {
        List<?> rawList = null;

        if (rawValue instanceof JsonArray jsonArray) {
            rawList = jsonArray.toList();
        } else if (rawValue instanceof List<?> list) {
            rawList = list;
        }

        if (rawList == null) {
            return null;
        }

        ArrayList<String> decoded = new ArrayList<>(rawList.size());

        for (Object item : rawList) {
            if (item == null) {
                decoded.add(null);
            } else if (item instanceof String text) {
                decoded.add(text);
            } else {
                decoded.add(String.valueOf(item));
            }
        }

        Object rawLength = content.get(FIELD_LENGTH);
        if (rawLength instanceof Number number && number.intValue() != decoded.size()) {
            log.warn(StructuredLog.event(
                    "repository_string_array_list_length_mismatch",
                    "expectedLength", number.intValue(),
                    "actualLength", decoded.size()
            ));
        }

        return StoredValue.stringArrayListValue(decoded);
    }

    private static StoredValue decodeStringHashMapStoredValue(JsonObject content, Object rawValue) {
        LinkedHashMap<String, String> decoded = new LinkedHashMap<>();

        if (rawValue instanceof JsonObject jsonObject) {
            for (String fieldName : jsonObject.getNames()) {
                Object item = jsonObject.get(fieldName);

                if (item == null) {
                    decoded.put(fieldName, null);
                } else if (item instanceof String text) {
                    decoded.put(fieldName, text);
                } else {
                    decoded.put(fieldName, String.valueOf(item));
                }
            }
        } else if (rawValue instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object item = entry.getValue();

                decoded.put(
                        key == null ? null : String.valueOf(key),
                        item == null ? null : String.valueOf(item)
                );
            }
        } else {
            return null;
        }

        Object rawLength = content.get(FIELD_LENGTH);
        if (rawLength instanceof Number number && number.intValue() != decoded.size()) {
            log.warn(StructuredLog.event(
                    "repository_string_hash_map_length_mismatch",
                    "expectedLength", number.intValue(),
                    "actualLength", decoded.size()
            ));
        }

        return StoredValue.stringHashMapValue(decoded);
    }


    private static StoredValue decodeStringObjectHashMapStoredValue(JsonObject content, Object rawValue) {
        LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();

        if (rawValue instanceof JsonObject jsonObject) {
            for (String fieldName : jsonObject.getNames()) {
                decoded.put(fieldName, decodeMapObjectValue(jsonObject.get(fieldName)));
            }
        } else if (rawValue instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();

                decoded.put(
                        key == null ? null : String.valueOf(key),
                        decodeMapObjectValue(entry.getValue())
                );
            }
        } else {
            return null;
        }

        Object rawLength = content.get(FIELD_LENGTH);
        if (rawLength instanceof Number number && number.intValue() != decoded.size()) {
            log.warn(StructuredLog.event(
                    "repository_string_object_hash_map_length_mismatch",
                    "expectedLength", number.intValue(),
                    "actualLength", decoded.size()
            ));
        }

        return StoredValue.stringObjectHashMapValue(decoded);
    }

    private static StoredValue decodeJavaSerializedObjectStoredValue(JsonObject content) {
        Object rawClassName = content.get("className");
        Object rawBase64 = content.get(FIELD_VALUE_BASE64);

        if (!(rawClassName instanceof String className) || className.isBlank()) {
            log.warn(StructuredLog.event(
                    "repository_java_serialized_object_decode_failed",
                    "reason", "missing_or_blank_className"
            ));
            return null;
        }

        if (!(rawBase64 instanceof String base64Text) || base64Text.isBlank()) {
            log.warn(StructuredLog.event(
                    "repository_java_serialized_object_decode_failed",
                    "reason", "missing_or_blank_valueBase64",
                    "className", className
            ));
            return null;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(base64Text);

            Object rawLength = content.get(FIELD_LENGTH);
            if (rawLength instanceof Number number && number.intValue() != decoded.length) {
                log.warn(StructuredLog.event(
                        "repository_java_serialized_object_length_mismatch",
                        "className", className,
                        "expectedLength", number.intValue(),
                        "actualLength", decoded.length
                ));
            }

            return StoredValue.javaSerializedObjectValue(className, decoded);
        } catch (IllegalArgumentException e) {
            log.warn(StructuredLog.event(
                    "repository_java_serialized_object_decode_failed",
                    "className", className,
                    "error", e.getMessage()
            ));
            return null;
        }
    }

    private static StoredValue decodeObjectArrayStoredValue(JsonObject content) {
        Object rawBase64 = content.get(FIELD_VALUE_BASE64);

        if (!(rawBase64 instanceof String base64Text) || base64Text.isBlank()) {
            log.warn(StructuredLog.event(
                    "repository_object_array_decode_failed",
                    "reason", "missing_or_blank_valueBase64"
            ));
            return null;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(base64Text);

            if (decoded.length == 0 || (decoded[0] & 0xff) != 0x34) {
                log.warn(StructuredLog.event(
                        "repository_object_array_decode_failed",
                        "reason", "decoded_value_does_not_start_with_object_array_marker",
                        "actualLength", decoded.length
                ));
                return null;
            }

            Object rawLength = content.get(FIELD_LENGTH);
            if (rawLength instanceof Number number && number.intValue() != decoded.length) {
                log.warn(StructuredLog.event(
                        "repository_object_array_length_mismatch",
                        "expectedLength", number.intValue(),
                        "actualLength", decoded.length
                ));
            }

            return StoredValue.objectArrayValue(decoded);
        } catch (IllegalArgumentException e) {
            log.warn(StructuredLog.event(
                    "repository_object_array_decode_failed",
                    "error", e.getMessage()
            ));
            return null;
        }
    }

    private static StoredValue decodeObjectArrayListStoredValue(JsonObject content) {
        Object rawBase64 = content.get(FIELD_VALUE_BASE64);

        if (!(rawBase64 instanceof String base64Text) || base64Text.isBlank()) {
            log.warn(StructuredLog.event(
                    "repository_object_array_list_decode_failed",
                    "reason", "missing_or_blank_valueBase64"
            ));
            return null;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(base64Text);

            if (decoded.length == 0 || (decoded[0] & 0xff) != 0x41) {
                log.warn(StructuredLog.event(
                        "repository_object_array_list_decode_failed",
                        "reason", "decoded_value_does_not_start_with_array_list_marker",
                        "actualLength", decoded.length
                ));
                return null;
            }

            Object rawLength = content.get(FIELD_LENGTH);
            if (rawLength instanceof Number number && number.intValue() != decoded.length) {
                log.warn(StructuredLog.event(
                        "repository_object_array_list_length_mismatch",
                        "expectedLength", number.intValue(),
                        "actualLength", decoded.length
                ));
            }

            return StoredValue.objectArrayListValue(decoded);
        } catch (IllegalArgumentException e) {
            log.warn(StructuredLog.event(
                    "repository_object_array_list_decode_failed",
                    "error", e.getMessage()
            ));
            return null;
        }
    }



    private static StoredValue decodeOpaqueGeodeValueStoredValue(JsonObject content) {
        Object rawTypeName = content.get(FIELD_OPAQUE_GEODE_TYPE_NAME);
        Object rawBase64 = content.get(FIELD_VALUE_BASE64);

        if (!(rawTypeName instanceof String typeName) || typeName.isBlank()) {
            log.warn(StructuredLog.event(
                    "repository_opaque_geode_value_decode_failed",
                    "reason", "missing_or_blank_opaqueGeodeTypeName"
            ));
            return null;
        }

        if (!(rawBase64 instanceof String base64Text) || base64Text.isBlank()) {
            log.warn(StructuredLog.event(
                    "repository_opaque_geode_value_decode_failed",
                    "reason", "missing_or_blank_valueBase64",
                    "opaqueGeodeTypeName", typeName
            ));
            return null;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(base64Text);

            if (decoded.length == 0) {
                log.warn(StructuredLog.event(
                        "repository_opaque_geode_value_decode_failed",
                        "reason", "decoded_value_empty",
                        "opaqueGeodeTypeName", typeName
                ));
                return null;
            }

            Object rawLength = content.get(FIELD_LENGTH);
            if (rawLength instanceof Number number && number.intValue() != decoded.length) {
                log.warn(StructuredLog.event(
                        "repository_opaque_geode_value_length_mismatch",
                        "opaqueGeodeTypeName", typeName,
                        "expectedLength", number.intValue(),
                        "actualLength", decoded.length
                ));
            }

            return StoredValue.opaqueGeodeValue(typeName, decoded);
        } catch (IllegalArgumentException e) {
            log.warn(StructuredLog.event(
                    "repository_opaque_geode_value_decode_failed",
                    "opaqueGeodeTypeName", typeName,
                    "error", e.getMessage()
            ));
            return null;
        }
    }

    private static StoredValue decodePdxInstanceStoredValue(JsonObject content) {
        Object rawBase64 = content.get(FIELD_VALUE_BASE64);

        if (!(rawBase64 instanceof String base64Text) || base64Text.isBlank()) {
            log.warn(StructuredLog.event(
                    "repository_pdx_instance_decode_failed",
                    "reason", "missing_or_blank_valueBase64"
            ));
            return null;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(base64Text);

            if (decoded.length == 0 || (decoded[0] & 0xff) != 0x5d) {
                log.warn(StructuredLog.event(
                        "repository_pdx_instance_decode_failed",
                        "reason", "decoded_value_does_not_start_with_pdx_marker",
                        "actualLength", decoded.length
                ));
                return null;
            }

            Object rawLength = content.get(FIELD_LENGTH);
            if (rawLength instanceof Number number && number.intValue() != decoded.length) {
                log.warn(StructuredLog.event(
                        "repository_pdx_instance_length_mismatch",
                        "expectedLength", number.intValue(),
                        "actualLength", decoded.length
                ));
            }

            return StoredValue.pdxInstanceValue(decoded);
        } catch (IllegalArgumentException e) {
            log.warn(StructuredLog.event(
                    "repository_pdx_instance_decode_failed",
                    "error", e.getMessage()
            ));
            return null;
        }
    }


    private static JsonObject encodeMapObjectValue(Object value) {
        JsonObject out = JsonObject.create();

        if (value == null) {
            out.put(FIELD_TYPE, "null");
            return out;
        }

        if (value instanceof String text) {
            out.put(FIELD_TYPE, TYPE_STRING);
            out.put(FIELD_VALUE, text);
            return out;
        }

        if (value instanceof Boolean bool) {
            out.put(FIELD_TYPE, TYPE_BOOLEAN);
            out.put(FIELD_VALUE, bool);
            return out;
        }

        if (value instanceof Character character) {
            out.put(FIELD_TYPE, TYPE_CHARACTER);
            out.put(FIELD_VALUE, String.valueOf(character));
            return out;
        }

        if (value instanceof Byte byteValue) {
            out.put(FIELD_TYPE, TYPE_BYTE);
            out.put(FIELD_VALUE, byteValue);
            return out;
        }

        if (value instanceof Short shortValue) {
            out.put(FIELD_TYPE, TYPE_SHORT);
            out.put(FIELD_VALUE, shortValue);
            return out;
        }

        if (value instanceof Integer integerValue) {
            out.put(FIELD_TYPE, TYPE_INTEGER);
            out.put(FIELD_VALUE, integerValue);
            return out;
        }

        if (value instanceof Long longValue) {
            out.put(FIELD_TYPE, TYPE_LONG);
            out.put(FIELD_VALUE, longValue);
            return out;
        }

        if (value instanceof Float floatValue) {
            out.put(FIELD_TYPE, TYPE_FLOAT);
            out.put(FIELD_VALUE, floatValue);
            return out;
        }

        if (value instanceof Double doubleValue) {
            out.put(FIELD_TYPE, TYPE_DOUBLE);
            out.put(FIELD_VALUE, doubleValue);
            return out;
        }

        if (value instanceof Date date) {
            out.put(FIELD_TYPE, TYPE_DATE);
            out.put(FIELD_VALUE, date.toInstant().toString());
            out.put(FIELD_EPOCH_MILLIS, date.getTime());
            return out;
        }

        if (value instanceof byte[] bytes) {
            out.put(FIELD_TYPE, TYPE_BYTE_ARRAY);
            out.put(FIELD_VALUE_BASE64, Base64.getEncoder().encodeToString(bytes));
            out.put(FIELD_LENGTH, bytes.length);
            return out;
        }

        if (value instanceof boolean[] booleans) {
            JsonArray jsonArray = JsonArray.create();

            for (boolean item : booleans) {
                jsonArray.add(item);
            }

            out.put(FIELD_TYPE, TYPE_BOOLEAN_ARRAY);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, booleans.length);
            return out;
        }

        if (value instanceof char[] chars) {
            JsonArray jsonArray = JsonArray.create();

            for (char item : chars) {
                jsonArray.add(String.valueOf(item));
            }

            out.put(FIELD_TYPE, TYPE_CHAR_ARRAY);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, chars.length);
            return out;
        }

        if (value instanceof short[] shorts) {
            JsonArray jsonArray = JsonArray.create();

            for (short item : shorts) {
                jsonArray.add(item);
            }

            out.put(FIELD_TYPE, TYPE_SHORT_ARRAY);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, shorts.length);
            return out;
        }

        if (value instanceof int[] ints) {
            JsonArray jsonArray = JsonArray.create();

            for (int item : ints) {
                jsonArray.add(item);
            }

            out.put(FIELD_TYPE, TYPE_INT_ARRAY);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, ints.length);
            return out;
        }

        if (value instanceof long[] longs) {
            JsonArray jsonArray = JsonArray.create();

            for (long item : longs) {
                jsonArray.add(item);
            }

            out.put(FIELD_TYPE, TYPE_LONG_ARRAY);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, longs.length);
            return out;
        }

        if (value instanceof float[] floats) {
            JsonArray jsonArray = JsonArray.create();

            for (float item : floats) {
                jsonArray.add(item);
            }

            out.put(FIELD_TYPE, TYPE_FLOAT_ARRAY);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, floats.length);
            return out;
        }

        if (value instanceof double[] doubles) {
            JsonArray jsonArray = JsonArray.create();

            for (double item : doubles) {
                jsonArray.add(item);
            }

            out.put(FIELD_TYPE, TYPE_DOUBLE_ARRAY);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, doubles.length);
            return out;
        }

        if (value instanceof String[] strings) {
            JsonArray jsonArray = JsonArray.create();

            for (String item : strings) {
                jsonArray.add(item);
            }

            out.put(FIELD_TYPE, TYPE_STRING_ARRAY);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, strings.length);
            return out;
        }

        if (value instanceof BigInteger bigInteger) {
            out.put(FIELD_TYPE, TYPE_BIG_INTEGER);
            out.put(FIELD_VALUE, bigInteger.toString());
            return out;
        }

        if (value instanceof BigDecimal bigDecimal) {
            out.put(FIELD_TYPE, TYPE_BIG_DECIMAL);
            out.put(FIELD_VALUE, bigDecimal.toString());
            return out;
        }

        if (value instanceof UUID uuid) {
            out.put(FIELD_TYPE, TYPE_UUID);
            out.put(FIELD_VALUE, uuid.toString());
            return out;
        }

        if (value instanceof Enum<?> enumValue) {
            // The enum class was loadable to reach the structured path (it came in via Java
            // deserialization of the map), so it is loadable again on read.
            out.put(FIELD_TYPE, TYPE_ENUM);
            out.put(FIELD_ENUM_CLASS, enumValue.getDeclaringClass().getName());
            out.put(FIELD_VALUE, enumValue.name());
            return out;
        }

        // Nested containers, encoded recursively so their scalar leaves stay queryable and the graph
        // round-trips exactly (element values + scalar runtime types preserved).
        if (value instanceof Object[] array) {
            JsonArray jsonArray = JsonArray.create();

            for (Object item : array) {
                jsonArray.add(encodeMapObjectValue(item));
            }

            out.put(FIELD_TYPE, TYPE_NESTED_OBJECT_ARRAY);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, array.length);
            return out;
        }

        if (value instanceof List<?> list) {
            JsonArray jsonArray = JsonArray.create();

            for (Object item : list) {
                jsonArray.add(encodeMapObjectValue(item));
            }

            out.put(FIELD_TYPE, TYPE_NESTED_LIST);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, list.size());
            return out;
        }

        if (value instanceof Map<?, ?> map) {
            JsonObject jsonMap = JsonObject.create();

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    jsonMap.put(String.valueOf(entry.getKey()), encodeMapObjectValue(entry.getValue()));
                }
            }

            out.put(FIELD_TYPE, TYPE_NESTED_MAP);
            out.put(FIELD_VALUE, jsonMap);
            out.put(FIELD_LENGTH, map.size());
            return out;
        }

        out.put(FIELD_TYPE, TYPE_STRING);
        out.put(FIELD_VALUE, String.valueOf(value));
        return out;
    }

    private static Object decodeMapObjectValue(Object rawValue) {
        if (rawValue == null) {
            return null;
        }

        JsonObject typedValue = null;

        if (rawValue instanceof JsonObject jsonObject) {
            typedValue = jsonObject;
        } else if (rawValue instanceof Map<?, ?> map) {
            typedValue = JsonObject.create();

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();

                if (key != null) {
                    typedValue.put(String.valueOf(key), entry.getValue());
                }
            }
        }

        if (typedValue == null) {
            return rawValue;
        }

        String type = typedValue.containsKey(FIELD_TYPE)
                ? typedValue.getString(FIELD_TYPE)
                : TYPE_STRING;

        Object value = typedValue.get(FIELD_VALUE);

        if ("null".equalsIgnoreCase(type)) {
            return null;
        }

        if (TYPE_STRING.equalsIgnoreCase(type)) {
            return value == null ? null : String.valueOf(value);
        }

        if (TYPE_BOOLEAN.equalsIgnoreCase(type)) {
            if (value instanceof Boolean bool) {
                return bool;
            }

            if (value instanceof String text) {
                return Boolean.valueOf(text);
            }

            return null;
        }

        if (TYPE_CHARACTER.equalsIgnoreCase(type)) {
            if (value instanceof String text && text.length() == 1) {
                return Character.valueOf(text.charAt(0));
            }

            return null;
        }

        if (TYPE_BYTE.equalsIgnoreCase(type)) {
            if (value instanceof Number number) {
                return Byte.valueOf(number.byteValue());
            }

            if (value instanceof String text) {
                try {
                    return Byte.valueOf(text);
                } catch (NumberFormatException e) {
                    return text;
                }
            }

            return null;
        }

        if (TYPE_SHORT.equalsIgnoreCase(type)) {
            if (value instanceof Number number) {
                return Short.valueOf(number.shortValue());
            }

            if (value instanceof String text) {
                try {
                    return Short.valueOf(text);
                } catch (NumberFormatException e) {
                    return text;
                }
            }

            return null;
        }

        if (TYPE_INTEGER.equalsIgnoreCase(type)) {
            if (value instanceof Number number) {
                return Integer.valueOf(number.intValue());
            }

            if (value instanceof String text) {
                try {
                    return Integer.valueOf(text);
                } catch (NumberFormatException e) {
                    return text;
                }
            }

            return null;
        }

        if (TYPE_LONG.equalsIgnoreCase(type)) {
            if (value instanceof Number number) {
                return Long.valueOf(number.longValue());
            }

            if (value instanceof String text) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException e) {
                    return text;
                }
            }

            return null;
        }

        if (TYPE_FLOAT.equalsIgnoreCase(type)) {
            if (value instanceof Number number) {
                return Float.valueOf(number.floatValue());
            }

            if (value instanceof String text) {
                try {
                    return Float.valueOf(text);
                } catch (NumberFormatException e) {
                    return text;
                }
            }

            return null;
        }

        if (TYPE_DOUBLE.equalsIgnoreCase(type)) {
            if (value instanceof Number number) {
                return Double.valueOf(number.doubleValue());
            }

            if (value instanceof String text) {
                try {
                    return Double.valueOf(text);
                } catch (NumberFormatException e) {
                    return text;
                }
            }

            return null;
        }

        if (TYPE_DATE.equalsIgnoreCase(type)) {
            Object rawEpochMillis = typedValue.get(FIELD_EPOCH_MILLIS);

            if (rawEpochMillis instanceof Number number) {
                return new Date(number.longValue());
            }

            if (rawEpochMillis instanceof String text) {
                try {
                    return new Date(Long.parseLong(text));
                } catch (NumberFormatException e) {
                    // Fall through and try value below.
                }
            }

            if (value instanceof Number number) {
                return new Date(number.longValue());
            }

            if (value instanceof String text) {
                try {
                    return Date.from(Instant.parse(text));
                } catch (DateTimeParseException e) {
                    try {
                        return new Date(Long.parseLong(text));
                    } catch (NumberFormatException ignored) {
                        return text;
                    }
                }
            }

            return null;
        }

        if (TYPE_BYTE_ARRAY.equalsIgnoreCase(type)) {
            Object rawBase64 = typedValue.get(FIELD_VALUE_BASE64);

            if (rawBase64 instanceof String base64Text) {
                try {
                    return Base64.getDecoder().decode(base64Text);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }

            if (value instanceof String text) {
                try {
                    return Base64.getDecoder().decode(text);
                } catch (IllegalArgumentException e) {
                    return text;
                }
            }

            return null;
        }

        if (TYPE_BOOLEAN_ARRAY.equalsIgnoreCase(type)) {
            List<?> rawList = rawListFromValue(value);

            if (rawList == null) {
                return null;
            }

            boolean[] decoded = new boolean[rawList.size()];

            for (int i = 0; i < rawList.size(); i++) {
                Object item = rawList.get(i);

                if (item instanceof Boolean bool) {
                    decoded[i] = bool;
                } else if (item instanceof String text) {
                    if ("true".equalsIgnoreCase(text)) {
                        decoded[i] = true;
                    } else if ("false".equalsIgnoreCase(text)) {
                        decoded[i] = false;
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            }

            return decoded;
        }

        if (TYPE_CHAR_ARRAY.equalsIgnoreCase(type)) {
            List<?> rawList = rawListFromValue(value);

            if (rawList == null) {
                return null;
            }

            char[] decoded = new char[rawList.size()];

            for (int i = 0; i < rawList.size(); i++) {
                Object item = rawList.get(i);

                if (item instanceof String text && text.length() == 1) {
                    decoded[i] = text.charAt(0);
                } else {
                    return null;
                }
            }

            return decoded;
        }

        if (TYPE_SHORT_ARRAY.equalsIgnoreCase(type)) {
            List<?> rawList = rawListFromValue(value);

            if (rawList == null) {
                return null;
            }

            short[] decoded = new short[rawList.size()];

            for (int i = 0; i < rawList.size(); i++) {
                Object item = rawList.get(i);

                if (item instanceof Number number) {
                    decoded[i] = number.shortValue();
                } else if (item instanceof String text) {
                    try {
                        decoded[i] = Short.parseShort(text);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            }

            return decoded;
        }

        if (TYPE_INT_ARRAY.equalsIgnoreCase(type)) {
            List<?> rawList = null;

            if (value instanceof JsonArray jsonArray) {
                rawList = jsonArray.toList();
            } else if (value instanceof List<?> list) {
                rawList = list;
            }

            if (rawList == null) {
                return null;
            }

            int[] decoded = new int[rawList.size()];

            for (int i = 0; i < rawList.size(); i++) {
                Object item = rawList.get(i);

                if (item instanceof Number number) {
                    decoded[i] = number.intValue();
                } else if (item instanceof String text) {
                    try {
                        decoded[i] = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            }

            return decoded;
        }

        if (TYPE_LONG_ARRAY.equalsIgnoreCase(type)) {
            List<?> rawList = rawListFromValue(value);

            if (rawList == null) {
                return null;
            }

            long[] decoded = new long[rawList.size()];

            for (int i = 0; i < rawList.size(); i++) {
                Object item = rawList.get(i);

                if (item instanceof Number number) {
                    decoded[i] = number.longValue();
                } else if (item instanceof String text) {
                    try {
                        decoded[i] = Long.parseLong(text);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            }

            return decoded;
        }

        if (TYPE_FLOAT_ARRAY.equalsIgnoreCase(type)) {
            List<?> rawList = rawListFromValue(value);

            if (rawList == null) {
                return null;
            }

            float[] decoded = new float[rawList.size()];

            for (int i = 0; i < rawList.size(); i++) {
                Object item = rawList.get(i);

                if (item instanceof Number number) {
                    decoded[i] = number.floatValue();
                } else if (item instanceof String text) {
                    try {
                        decoded[i] = Float.parseFloat(text);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            }

            return decoded;
        }

        if (TYPE_DOUBLE_ARRAY.equalsIgnoreCase(type)) {
            List<?> rawList = rawListFromValue(value);

            if (rawList == null) {
                return null;
            }

            double[] decoded = new double[rawList.size()];

            for (int i = 0; i < rawList.size(); i++) {
                Object item = rawList.get(i);

                if (item instanceof Number number) {
                    decoded[i] = number.doubleValue();
                } else if (item instanceof String text) {
                    try {
                        decoded[i] = Double.parseDouble(text);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            }

            return decoded;
        }

        if (TYPE_STRING_ARRAY.equalsIgnoreCase(type)) {
            List<?> rawList = null;

            if (value instanceof JsonArray jsonArray) {
                rawList = jsonArray.toList();
            } else if (value instanceof List<?> list) {
                rawList = list;
            }

            if (rawList == null) {
                return null;
            }

            String[] decoded = new String[rawList.size()];

            for (int i = 0; i < rawList.size(); i++) {
                Object item = rawList.get(i);
                decoded[i] = item == null ? null : String.valueOf(item);
            }

            return decoded;
        }

        if (TYPE_STRING_ARRAY_LIST.equalsIgnoreCase(type)) {
            List<?> rawList = null;

            if (value instanceof JsonArray jsonArray) {
                rawList = jsonArray.toList();
            } else if (value instanceof List<?> list) {
                rawList = list;
            }

            if (rawList == null) {
                return null;
            }

            ArrayList<String> decoded = new ArrayList<>(rawList.size());

            for (Object item : rawList) {
                decoded.add(item == null ? null : String.valueOf(item));
            }

            return decoded;
        }

        if (TYPE_BIG_INTEGER.equalsIgnoreCase(type)) {
            if (value instanceof String text) {
                try {
                    return new BigInteger(text);
                } catch (NumberFormatException e) {
                    return text;
                }
            }
            if (value instanceof Number number) {
                return BigInteger.valueOf(number.longValue());
            }
            return null;
        }

        if (TYPE_BIG_DECIMAL.equalsIgnoreCase(type)) {
            if (value instanceof String text) {
                try {
                    return new BigDecimal(text);
                } catch (NumberFormatException e) {
                    return text;
                }
            }
            if (value instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue());
            }
            return null;
        }

        if (TYPE_UUID.equalsIgnoreCase(type)) {
            if (value instanceof String text) {
                try {
                    return UUID.fromString(text);
                } catch (IllegalArgumentException e) {
                    return text;
                }
            }
            return null;
        }

        if (TYPE_ENUM.equalsIgnoreCase(type)) {
            Object rawClass = typedValue.get(FIELD_ENUM_CLASS);

            if (value instanceof String name && rawClass instanceof String className) {
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object decoded = Enum.valueOf((Class<? extends Enum>) Class.forName(className), name);
                    return decoded;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Enum class not on this shim's classpath at read time: degrade to the constant name.
                    return name;
                }
            }

            return value == null ? null : String.valueOf(value);
        }

        if (TYPE_NESTED_OBJECT_ARRAY.equalsIgnoreCase(type)) {
            List<?> rawList = rawListFromValue(value);

            if (rawList == null) {
                return null;
            }

            Object[] decoded = new Object[rawList.size()];

            for (int i = 0; i < rawList.size(); i++) {
                decoded[i] = decodeMapObjectValue(rawList.get(i));
            }

            return decoded;
        }

        if (TYPE_NESTED_LIST.equalsIgnoreCase(type)) {
            List<?> rawList = rawListFromValue(value);

            if (rawList == null) {
                return null;
            }

            ArrayList<Object> decoded = new ArrayList<>(rawList.size());

            for (Object item : rawList) {
                decoded.add(decodeMapObjectValue(item));
            }

            return decoded;
        }

        if (TYPE_NESTED_MAP.equalsIgnoreCase(type)) {
            JsonObject nested = null;

            if (value instanceof JsonObject jsonObject) {
                nested = jsonObject;
            } else if (value instanceof Map<?, ?> map) {
                nested = JsonObject.create();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        nested.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }

            if (nested == null) {
                return null;
            }

            LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();

            for (String name : nested.getNames()) {
                decoded.put(name, decodeMapObjectValue(nested.get(name)));
            }

            return decoded;
        }

        return value == null ? null : String.valueOf(value);
    }

    private static StoredValue decodeDateStoredValue(JsonObject content, Object rawValue) {
        Object rawEpochMillis = content.get(FIELD_EPOCH_MILLIS);

        if (rawEpochMillis instanceof Number number) {
            return StoredValue.dateValue(new Date(number.longValue()));
        }

        if (rawEpochMillis instanceof String text) {
            try {
                return StoredValue.dateValue(new Date(Long.parseLong(text)));
            } catch (NumberFormatException e) {
                // Fall through and try the value field below.
            }
        }

        if (rawValue instanceof Number number) {
            return StoredValue.dateValue(new Date(number.longValue()));
        }

        if (rawValue instanceof String text) {
            try {
                return StoredValue.dateValue(Date.from(Instant.parse(text)));
            } catch (DateTimeParseException e) {
                try {
                    return StoredValue.dateValue(new Date(Long.parseLong(text)));
                } catch (NumberFormatException ignored) {
                    return StoredValue.stringValue(text);
                }
            }

        }

        return null;
    }

    private static String q(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
