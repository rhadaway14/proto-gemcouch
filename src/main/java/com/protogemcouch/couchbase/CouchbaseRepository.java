package com.protogemcouch.couchbase;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import com.protogemcouch.config.ServerConfig;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.DocumentKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CouchbaseRepository implements Repository {

    private static final Logger log = LoggerFactory.getLogger(CouchbaseRepository.class);

    private static final String FIELD_VALUE = "value";
    private static final String FIELD_VALUE_BASE64 = "valueBase64";
    private static final String FIELD_LENGTH = "length";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_EPOCH_MILLIS = "epochMillis";

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
    private static final String TYPE_SHORT = "short";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_DOUBLE = "double";
    private static final String TYPE_DATE = "date";

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
    public StoredValue get(String docId) {
        try {
            GetResult result = collection.get(docId);
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
            log.warn(StructuredLog.event(
                    "repository_get_miss_or_error",
                    "docId", docId,
                    "error", e.getMessage()
            ));
            return null;
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

        JsonObject body = encodeStoredValue(value);
        collection.upsert(docId, body);

        log.info(StructuredLog.event(
                "repository_put_ok",
                "docId", docId,
                "valueType", value.type()
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
        } catch (DocumentNotFoundException e) {
            log.info(StructuredLog.event(
                    "repository_remove_miss",
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

    private static JsonObject encodeStoredValue(StoredValue value) {
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

    private static StoredValue decodeStoredValue(JsonObject content) {
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

        if (value instanceof ArrayList<?> list) {
            JsonArray jsonArray = JsonArray.create();

            for (Object item : list) {
                jsonArray.add(item == null ? null : String.valueOf(item));
            }

            out.put(FIELD_TYPE, TYPE_STRING_ARRAY_LIST);
            out.put(FIELD_VALUE, jsonArray);
            out.put(FIELD_LENGTH, list.size());
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
