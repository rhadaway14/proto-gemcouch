package com.protogemcouch.serialization;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ValueDecoding {

    private static final int GEODE_STRING_CODE = 0x57;
    private static final int GEODE_NULL_CODE = 0x29;

    /*
     * Geode DataSerializer String[] marker observed from StringArrayShapeTest:
     *
     *   new String[] {}                         -> 40 00
     *   new String[] {"one"}                    -> 40 01 57 00 03 6f 6e 65
     *   new String[] {"one","two","three"}     -> 40 03 57 00 03 6f 6e 65 57 00 03 74 77 6f 57 00 05 74 68 72 65 65
     *   new String[] {"","A","hello"}          -> 40 03 57 00 00 57 00 01 41 57 00 05 68 65 6c 6c 6f
     *   new String[] {"one",null,"three"}      -> 40 03 57 00 03 6f 6e 65 45 57 00 05 74 68 72 65 65
     *
     * String elements use the normal Geode string marker 0x57.
     * Null string-array elements use marker 0x45.
     */
    private static final int GEODE_STRING_ARRAY_CODE = 0x40;
    private static final int GEODE_NULL_STRING_ARRAY_ELEMENT_CODE = 0x45;

    /*
     * Geode DataSerializer ArrayList marker observed from StringArrayListShapeTest:
     *
     *   new ArrayList<>()                         -> 41 00
     *   ["one"]                                   -> 41 01 57 00 03 6f 6e 65
     *   ["one","two","three"]                    -> 41 03 57 00 03 6f 6e 65 57 00 03 74 77 6f 57 00 05 74 68 72 65 65
     *   ["","A","hello"]                         -> 41 03 57 00 00 57 00 01 41 57 00 05 68 65 6c 6c 6f
     *   ["one",null,"three"]                     -> 41 03 57 00 03 6f 6e 65 29 57 00 05 74 68 72 65 65
     *
     * String elements use the normal Geode string marker 0x57.
     * Null ArrayList elements use the normal Geode null marker 0x29.
     */
    private static final int GEODE_STRING_ARRAY_LIST_CODE = 0x41;

    /*
     * Geode DataSerializer ArrayList<Object> uses the same 0x41 list marker
     * as ArrayList<String>, but elements may be mixed DataSerializer values.
     *
     * Decode order matters:
     *   1. Try decodeStringArrayListValue(...) first.
     *   2. If that fails and the payload starts with 0x41, preserve the
     *      complete encoded payload as an opaque Object ArrayList.
     */
    private static final int GEODE_OBJECT_ARRAY_LIST_CODE = 0x41;

    /*
     * Geode DataSerializer HashMap/LinkedHashMap observations from HashMapStringStringShapeTest:
     *
     *   new HashMap<>()                         -> 43 00
     *   non-empty LinkedHashMap<String,String>  -> 2c ac ed 00 05 ...
     *
     * Empty maps use compact marker 0x43 + size 0.
     * Non-empty LinkedHashMap payloads are Java-serialized behind marker 0x2c.
     */
    private static final int GEODE_HASH_MAP_CODE = 0x43;
    private static final int GEODE_JAVA_SERIALIZED_CODE = 0x2c;

    /*
     * Geode DataSerializer Object[] marker observed from ObjectArrayShapeTest:
     *
     *   34 <length> 2b 57 0010 "java.lang.Object" <elements...>
     *
     * For the first compatibility pass we preserve the complete Object[]
     * payload as opaque bytes. This avoids needing to load nested customer
     * POJO classes inside the shim and safely handles nested 2c Java-serialized
     * objects whose exact byte length is non-trivial to scan.
     */
    private static final int GEODE_OBJECT_ARRAY_CODE = 0x34;

    /*
     * Geode DataSerializer byte[] marker observed from ByteArrayShapeTest:
     *
     *   new byte[] {}                         -> 2e 00
     *   new byte[] {0x01}                     -> 2e 01 01
     *   new byte[] {0x01,2,3,4,5}             -> 2e 05 01 02 03 04 05
     *   new byte[] {0,1,0x7f,0x80,0xff}       -> 2e 05 00 01 7f 80 ff
     *
     * This implementation supports the validated compact/small-length shape.
     */
    private static final int GEODE_BYTE_ARRAY_CODE = 0x2e;

    /*
     * Geode DataSerializer primitive int[] marker observed from PrimitiveArrayShapeTest:
     *
     *   new int[] {}                                      -> 30 00
     *   new int[] {1,42,-7,Integer.MAX_VALUE,MIN_VALUE}   -> 30 05 00000001 0000002a fffffff9 7fffffff 80000000
     *
     * Values are stored big-endian, four bytes per int.
     */
    private static final int GEODE_INT_ARRAY_CODE = 0x30;

    /*
     * Geode DataSerializer primitive array markers observed from PrimitiveArrayShapeTest:
     *
     *   boolean[]  -> 1a <length> <1-byte boolean values>
     *   char[]     -> 1b <length> <2-byte big-endian char values>
     *   short[]    -> 2f <length> <2-byte big-endian short values>
     *   long[]     -> 31 <length> <8-byte big-endian long values>
     *   float[]    -> 32 <length> <4-byte big-endian IEEE-754 float bits>
     *   double[]   -> 33 <length> <8-byte big-endian IEEE-754 double bits>
     */
    private static final int GEODE_BOOLEAN_ARRAY_CODE = 0x1a;
    private static final int GEODE_CHAR_ARRAY_CODE = 0x1b;
    private static final int GEODE_SHORT_ARRAY_CODE = 0x2f;
    private static final int GEODE_LONG_ARRAY_CODE = 0x31;
    private static final int GEODE_FLOAT_ARRAY_CODE = 0x32;
    private static final int GEODE_DOUBLE_ARRAY_CODE = 0x33;

    private static final int GEODE_BOOLEAN_CODE = 0x35;
    private static final int GEODE_CHARACTER_CODE = 0x36;
    private static final int GEODE_BYTE_CODE = 0x37;
    private static final int GEODE_SHORT_CODE = 0x38;
    private static final int GEODE_INTEGER_CODE = 0x39;
    private static final int GEODE_LONG_CODE = 0x3a;
    private static final int GEODE_FLOAT_CODE = 0x3b;
    private static final int GEODE_DOUBLE_CODE = 0x3c;
    private static final int GEODE_DATE_CODE = 0x3d;

    /*
     * Standalone utility value markers observed from WrapperArrayAndUtilityShapeTest:
     *
     *   BigInteger  -> 0x5f
     *   BigDecimal  -> 0x60
     *   UUID        -> 0x62
     *   Enum        -> 0x65
     *
     * These are preserved opaquely first. The Geode client can deserialize them
     * correctly when the original marker + payload bytes are returned.
     */
    private static final int GEODE_BIG_INTEGER_CODE = 0x5f;
    private static final int GEODE_BIG_DECIMAL_CODE = 0x60;
    private static final int GEODE_UUID_CODE = 0x62;
    private static final int GEODE_ENUM_CODE = 0x65;

    /*
     * Geode PDX / PdxInstance marker observed from PdxShapeTest:
     *
     *   PdxInstance -> 0x5d <payload...>
     *
     * PDX values can depend on Geode type metadata and should be preserved
     * opaquely for the compatibility-first path.
     */
    private static final int GEODE_PDX_INSTANCE_CODE = 0x5d;

    /*
     * Geode DataSerializable marker (DSCODE.DATA_SERIALIZABLE). Confirmed by capturing a real
     * client value:
     *
     *   2d 2b 57 <len2> <class-name-utf8> <toData bytes...>
     *      (0x2d DATA_SERIALIZABLE, 0x2b CLASS, 0x57 string + class name, then toData)
     *
     * The shim has none of the user's DataSerializable classes and the payload carries no schema, so
     * the value is preserved opaquely (verbatim) and the client re-instantiates it via its own
     * fromData. Field-level querying is therefore not possible (unlike PDX, which is self-describing).
     */
    private static final int GEODE_DATA_SERIALIZABLE_CODE = 0x2d;

    private ValueDecoding() {
    }

    public record JavaSerializedObject(String className, byte[] serializedValue) {
        public JavaSerializedObject {
            if (className == null || className.isBlank()) {
                throw new IllegalArgumentException("className must not be blank");
            }

            if (serializedValue == null || serializedValue.length == 0) {
                throw new IllegalArgumentException("serializedValue must not be null or empty");
            }

            serializedValue = Arrays.copyOf(serializedValue, serializedValue.length);
        }

        @Override
        public byte[] serializedValue() {
            return Arrays.copyOf(serializedValue, serializedValue.length);
        }
    }

    public record ObjectArray(byte[] encodedValue) {
        public ObjectArray {
            if (encodedValue == null || encodedValue.length == 0) {
                throw new IllegalArgumentException("encodedValue must not be null or empty");
            }

            encodedValue = Arrays.copyOf(encodedValue, encodedValue.length);
        }

        @Override
        public byte[] encodedValue() {
            return Arrays.copyOf(encodedValue, encodedValue.length);
        }
    }

    public record ObjectArrayList(byte[] encodedValue) {
        public ObjectArrayList {
            if (encodedValue == null || encodedValue.length == 0) {
                throw new IllegalArgumentException("encodedValue must not be null or empty");
            }

            encodedValue = Arrays.copyOf(encodedValue, encodedValue.length);
        }

        @Override
        public byte[] encodedValue() {
            return Arrays.copyOf(encodedValue, encodedValue.length);
        }
    }

    public record OpaqueGeodeValue(String typeName, byte[] encodedValue) {
        public OpaqueGeodeValue {
            if (typeName == null || typeName.isBlank()) {
                throw new IllegalArgumentException("typeName must not be blank");
            }

            if (encodedValue == null || encodedValue.length == 0) {
                throw new IllegalArgumentException("encodedValue must not be null or empty");
            }

            encodedValue = Arrays.copyOf(encodedValue, encodedValue.length);
        }

        @Override
        public byte[] encodedValue() {
            return Arrays.copyOf(encodedValue, encodedValue.length);
        }
    }

    public record PdxInstanceValue(byte[] encodedValue) {
        public PdxInstanceValue {
            if (encodedValue == null || encodedValue.length == 0) {
                throw new IllegalArgumentException("encodedValue must not be null or empty");
            }

            encodedValue = Arrays.copyOf(encodedValue, encodedValue.length);
        }

        @Override
        public byte[] encodedValue() {
            return Arrays.copyOf(encodedValue, encodedValue.length);
        }
    }



    public static ObjectArray decodeObjectArrayValue(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_OBJECT_ARRAY_CODE) {
            return null;
        }

        /*
         * Geode DataSerializer object-array style payloads use marker 0x34:
         *
         *   34 <length> 2b <component-type-string> <elements...>
         *
         * This covers both Object[] and component-specific wrapper / utility
         * arrays such as:
         *
         *   java.lang.Integer[]
         *   java.lang.Long[]
         *   java.lang.Boolean[]
         *   java.lang.Double[]
         *   java.util.UUID[]
         *   java.math.BigInteger[]
         *   java.math.BigDecimal[]
         *   java.time.Instant[]
         *   java.time.LocalDate[]
         *   java.time.LocalDateTime[]
         *
         * Preserve the whole payload opaquely. Returning the original 0x34
         * payload lets the Geode client deserialize the exact original array
         * type.
         */
        int offset = 1;

        int count = payload[offset] & 0xff;
        offset++;

        if (count > payload.length) {
            return null;
        }

        if (offset >= payload.length || (payload[offset] & 0xff) != 0x2b) {
            return null;
        }

        offset++;

        DecodedString componentType = decodeLengthPrefixedGeodeStringAt(payload, offset);

        if (componentType == null || componentType.value() == null || componentType.value().isBlank()) {
            return null;
        }

        if (componentType.nextOffset() > payload.length) {
            return null;
        }

        /*
         * Do not parse the element stream. Elements can include nulls, primitive
         * wrappers, utility values, enums, Java-serialized objects, and customer
         * classes. Opaque preservation is safer.
         */
        return new ObjectArray(payload);
    }

    public static ObjectArrayList decodeObjectArrayListValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_OBJECT_ARRAY_LIST_CODE) {
            return null;
        }

        /*
         * Keep string-only ArrayList values on the existing structured path.
         *
         * Examples that remain STRING_ARRAY_LIST:
         *   4100
         *   41015700036f6e65
         *   41035700036f6e65295700057468726565
         */
        if (decodeStringArrayListValue(payload) != null) {
            return null;
        }

        /*
         * Mixed ArrayList<Object> shape observed from ObjectArrayListShapeTest:
         *
         *   41 <length> <element>...
         *
         * Elements can include scalars, byte[], String[], Object[], nested
         * ArrayList, Java-serialized maps, and Java-serialized POJOs. We preserve
         * the whole payload opaquely to avoid classloading and Java serialization
         * stream-boundary parsing inside the shim.
         */
        int count = payload[1] & 0xff;

        if (count == 0) {
            /*
             * Empty list already decodes as STRING_ARRAY_LIST above. If we ever
             * arrive here, treat it as invalid rather than duplicating behavior.
             */
            return null;
        }

        if (payload.length < 3) {
            return null;
        }

        return new ObjectArrayList(payload);
    }


    public static String[] decodeStringArrayValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_STRING_ARRAY_CODE) {
            return null;
        }

        int count = payload[1] & 0xff;
        String[] values = new String[count];

        int offset = 2;

        for (int i = 0; i < count; i++) {
            if (offset >= payload.length) {
                return null;
            }

            int marker = payload[offset] & 0xff;

            if (marker == GEODE_NULL_STRING_ARRAY_ELEMENT_CODE) {
                values[i] = null;
                offset++;
                continue;
            }

            if (marker != GEODE_STRING_CODE) {
                return null;
            }

            DecodedString decoded = decodeLengthPrefixedGeodeStringAt(payload, offset);

            if (decoded == null) {
                return null;
            }

            values[i] = decoded.value();
            offset = decoded.nextOffset();
        }

        if (offset != payload.length) {
            return null;
        }

        return values;
    }

    public static ArrayList<String> decodeStringArrayListValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_STRING_ARRAY_LIST_CODE) {
            return null;
        }

        int count = payload[1] & 0xff;
        ArrayList<String> values = new ArrayList<>(count);

        int offset = 2;

        for (int i = 0; i < count; i++) {
            if (offset >= payload.length) {
                return null;
            }

            int marker = payload[offset] & 0xff;

            if (marker == GEODE_NULL_CODE) {
                values.add(null);
                offset++;
                continue;
            }

            if (marker != GEODE_STRING_CODE) {
                return null;
            }

            DecodedString decoded = decodeLengthPrefixedGeodeStringAt(payload, offset);

            if (decoded == null) {
                return null;
            }

            values.add(decoded.value());
            offset = decoded.nextOffset();
        }

        if (offset != payload.length) {
            return null;
        }

        return values;
    }

    public static LinkedHashMap<String, String> decodeStringHashMapValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        int first = payload[0] & 0xff;

        /*
         * Empty HashMap compact shape observed from Geode:
         *
         *   43 00
         */
        if (first == GEODE_HASH_MAP_CODE) {
            if (payload.length == 2 && (payload[1] & 0xff) == 0x00) {
                return new LinkedHashMap<>();
            }

            return null;
        }

        /*
         * Non-empty LinkedHashMap<String,String> shape observed from real Geode:
         *
         *   2c ac ed 00 05 ...
         *
         * The leading 0x2c is Geode's "Java serialized object" marker.
         * The bytes after 0x2c are a normal Java ObjectOutputStream payload.
         *
         * Do NOT use Geode DataSerializer here. In the shim container this can fail
         * with:
         *
         *   Could not initialize class org.apache.geode.DataSerializer
         *
         * Plain ObjectInputStream is sufficient for this specific shape and avoids
         * coupling map support to Geode's static serializer initialization.
         */
        if (first == GEODE_JAVA_SERIALIZED_CODE) {
            Object rawValue = deserializeJavaObjectAfterMarker(payload);

            if (rawValue instanceof Map<?, ?> rawMap && isStringStringMap(rawMap)) {
                return toStringStringLinkedHashMap(rawMap);
            }

            return null;
        }

        /*
         * Defensive fallback for cases where a test or future decoder hands us raw
         * Java serialization bytes without the Geode 0x2c marker.
         */
        if (payload.length >= 2
                && (payload[0] & 0xff) == 0xac
                && (payload[1] & 0xff) == 0xed) {
            Object rawValue = deserializeJavaObject(payload, 0, payload.length);

            if (rawValue instanceof Map<?, ?> rawMap && isStringStringMap(rawMap)) {
                return toStringStringLinkedHashMap(rawMap);
            }
        }

        return null;
    }



    public static LinkedHashMap<String, Object> decodeStringObjectHashMapValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        int first = payload[0] & 0xff;

        /*
         * Empty HashMap compact shape observed from Geode:
         *
         *   43 00
         */
        if (first == GEODE_HASH_MAP_CODE) {
            if (payload.length == 2 && (payload[1] & 0xff) == 0x00) {
                return new LinkedHashMap<>();
            }

            return null;
        }

        /*
         * Non-empty LinkedHashMap<String,Object> shape observed from Geode:
         *
         *   2c ac ed 00 05 ...
         *
         * The leading 0x2c is Geode's Java-serialized object marker.
         * The bytes after 0x2c are normal Java ObjectOutputStream bytes.
         *
         * Important strategy:
         *
         *   - If every nested map value is part of the supported structured
         *     map profile, decode to LinkedHashMap<String,Object> so Couchbase
         *     gets a queryable stringObjectHashMap envelope.
         *
         *   - If the map contains complex nested values such as Object[],
         *     ArrayList<Object>, Serializable POJOs, UUID, BigInteger,
         *     BigDecimal, Enum, wrapper arrays, or java.time arrays, return
         *     null here. The caller should then fall through to
         *     decodeJavaSerializedObjectValue(...) / JAVA_SERIALIZED_OBJECT
         *     preservation, which is the safest compatibility behavior for
         *     complex nested object graphs.
         */
        if (first == GEODE_JAVA_SERIALIZED_CODE) {
            Object rawValue = deserializeJavaObjectAfterMarker(payload);

            if (rawValue instanceof Map<?, ?> rawMap && isSupportedStringObjectMap(rawMap)) {
                return toStringObjectLinkedHashMap(rawMap);
            }

            return null;
        }

        /*
         * Defensive fallback for raw Java serialization bytes without the
         * Geode 0x2c marker.
         */
        if (looksLikeJavaSerializationStream(payload)) {
            Object rawValue = deserializeJavaObject(payload, 0, payload.length);

            if (rawValue instanceof Map<?, ?> rawMap && isSupportedStringObjectMap(rawMap)) {
                return toStringObjectLinkedHashMap(rawMap);
            }
        }

        return null;
    }


    public static JavaSerializedObject decodeOpaqueComplexMapValue(byte[] payload) {
        if (payload == null || payload.length < 5) {
            return null;
        }

        int first = payload[0] & 0xff;

        if (first == GEODE_JAVA_SERIALIZED_CODE) {
            Object rawValue = deserializeJavaObjectAfterMarker(payload);

            if (rawValue instanceof Map<?, ?> rawMap && !isSupportedStringObjectMap(rawMap)) {
                byte[] serializedValue = Arrays.copyOfRange(payload, 1, payload.length);

                return new JavaSerializedObject(
                        extractJavaSerializedClassName(serializedValue),
                        serializedValue
                );
            }

            return null;
        }

        if (looksLikeJavaSerializationStream(payload)) {
            Object rawValue = deserializeJavaObject(payload, 0, payload.length);

            if (rawValue instanceof Map<?, ?> rawMap && !isSupportedStringObjectMap(rawMap)) {
                byte[] serializedValue = Arrays.copyOf(payload, payload.length);

                return new JavaSerializedObject(
                        extractJavaSerializedClassName(serializedValue),
                        serializedValue
                );
            }
        }

        return null;
    }

    public static JavaSerializedObject decodeJavaSerializedObjectValue(byte[] payload) {
        if (payload == null || payload.length < 5) {
            return null;
        }

        int first = payload[0] & 0xff;

        if (first == GEODE_JAVA_SERIALIZED_CODE) {
            if (!looksLikeJavaSerializationStream(payload, 1, payload.length - 1)) {
                return null;
            }

            byte[] serializedValue = Arrays.copyOfRange(payload, 1, payload.length);

            return new JavaSerializedObject(
                    extractJavaSerializedClassName(serializedValue),
                    serializedValue
            );
        }

        if (looksLikeJavaSerializationStream(payload, 0, payload.length)) {
            byte[] serializedValue = Arrays.copyOf(payload, payload.length);

            return new JavaSerializedObject(
                    extractJavaSerializedClassName(serializedValue),
                    serializedValue
            );
        }

        return null;
    }

    public static OpaqueGeodeValue decodeOpaqueStandaloneUtilityValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        int first = payload[0] & 0xff;

        if (first == GEODE_UUID_CODE) {
            /*
             * Observed UUID shape:
             *
             *   62 <16 UUID bytes>
             */
            if (payload.length != 17) {
                return null;
            }

            return new OpaqueGeodeValue("uuid", payload);
        }

        if (first == GEODE_BIG_INTEGER_CODE) {
            return new OpaqueGeodeValue("bigInteger", payload);
        }

        if (first == GEODE_BIG_DECIMAL_CODE) {
            return new OpaqueGeodeValue("bigDecimal", payload);
        }

        if (first == GEODE_ENUM_CODE) {
            return new OpaqueGeodeValue("enum", payload);
        }

        if (first == GEODE_DATA_SERIALIZABLE_CODE) {
            // Preserve the whole DataSerializable payload verbatim so the client re-instantiates it
            // via its own fromData; the shim cannot (and need not) load the class.
            return new OpaqueGeodeValue(dataSerializableTypeName(payload), payload);
        }

        return null;
    }

    /**
     * Best-effort class name for a DataSerializable payload (for logs/observability only — the
     * preserved bytes are authoritative). Parses the {@code 2d 2b 57 <len2> <class-name>} prefix,
     * falling back to {@code "dataSerializable"} for any other shape (e.g. a registered-Instantiator
     * USER_CLASS id form, which is out of scope).
     */
    private static String dataSerializableTypeName(byte[] payload) {
        try {
            if (payload.length >= 5
                    && (payload[1] & 0xff) == 0x2b      // DSCODE.CLASS
                    && (payload[2] & 0xff) == GEODE_STRING_CODE) {
                int len = ((payload[3] & 0xff) << 8) | (payload[4] & 0xff);
                if (len > 0 && 5 + len <= payload.length) {
                    return "dataSerializable:"
                            + new String(payload, 5, len, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the generic name
        }
        return "dataSerializable";
    }

    public static PdxInstanceValue decodePdxInstanceValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_PDX_INSTANCE_CODE) {
            return null;
        }

        /*
         * PDX shape discovery showed every PdxInstance payload starts with 0x5d.
         * Preserve the full payload opaquely. The payload may rely on Geode PDX
         * type metadata, so parsing fields inside the shim is intentionally
         * deferred.
         */
        return new PdxInstanceValue(payload);
    }



    public static byte[] decodeByteArrayValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_BYTE_ARRAY_CODE) {
            return null;
        }

        int length = payload[1] & 0xff;

        if (payload.length != length + 2) {
            return null;
        }

        return Arrays.copyOfRange(payload, 2, payload.length);
    }

    public static int[] decodeIntArrayValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_INT_ARRAY_CODE) {
            return null;
        }

        int length = payload[1] & 0xff;

        if (payload.length != 2 + (length * Integer.BYTES)) {
            return null;
        }

        int[] values = new int[length];
        int offset = 2;

        for (int i = 0; i < length; i++) {
            values[i] = ((payload[offset] & 0xff) << 24)
                    | ((payload[offset + 1] & 0xff) << 16)
                    | ((payload[offset + 2] & 0xff) << 8)
                    | (payload[offset + 3] & 0xff);

            offset += Integer.BYTES;
        }

        return values;
    }

    public static boolean[] decodeBooleanArrayValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_BOOLEAN_ARRAY_CODE) {
            return null;
        }

        int length = payload[1] & 0xff;

        if (payload.length != 2 + length) {
            return null;
        }

        boolean[] values = new boolean[length];
        int offset = 2;

        for (int i = 0; i < length; i++) {
            int raw = payload[offset] & 0xff;

            if (raw == 0x00) {
                values[i] = false;
            } else if (raw == 0x01) {
                values[i] = true;
            } else {
                return null;
            }

            offset++;
        }

        return values;
    }

    public static char[] decodeCharArrayValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_CHAR_ARRAY_CODE) {
            return null;
        }

        int length = payload[1] & 0xff;

        if (payload.length != 2 + (length * Character.BYTES)) {
            return null;
        }

        char[] values = new char[length];
        int offset = 2;

        for (int i = 0; i < length; i++) {
            int value = ((payload[offset] & 0xff) << 8)
                    | (payload[offset + 1] & 0xff);

            values[i] = (char) value;
            offset += Character.BYTES;
        }

        return values;
    }

    public static short[] decodeShortArrayValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_SHORT_ARRAY_CODE) {
            return null;
        }

        int length = payload[1] & 0xff;

        if (payload.length != 2 + (length * Short.BYTES)) {
            return null;
        }

        short[] values = new short[length];
        int offset = 2;

        for (int i = 0; i < length; i++) {
            int value = ((payload[offset] & 0xff) << 8)
                    | (payload[offset + 1] & 0xff);

            values[i] = (short) value;
            offset += Short.BYTES;
        }

        return values;
    }

    public static long[] decodeLongArrayValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_LONG_ARRAY_CODE) {
            return null;
        }

        int length = payload[1] & 0xff;

        if (payload.length != 2 + (length * Long.BYTES)) {
            return null;
        }

        long[] values = new long[length];
        int offset = 2;

        for (int i = 0; i < length; i++) {
            values[i] = ((long) (payload[offset] & 0xff) << 56)
                    | ((long) (payload[offset + 1] & 0xff) << 48)
                    | ((long) (payload[offset + 2] & 0xff) << 40)
                    | ((long) (payload[offset + 3] & 0xff) << 32)
                    | ((long) (payload[offset + 4] & 0xff) << 24)
                    | ((long) (payload[offset + 5] & 0xff) << 16)
                    | ((long) (payload[offset + 6] & 0xff) << 8)
                    | ((long) (payload[offset + 7] & 0xff));

            offset += Long.BYTES;
        }

        return values;
    }

    public static float[] decodeFloatArrayValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_FLOAT_ARRAY_CODE) {
            return null;
        }

        int length = payload[1] & 0xff;

        if (payload.length != 2 + (length * Float.BYTES)) {
            return null;
        }

        float[] values = new float[length];
        int offset = 2;

        for (int i = 0; i < length; i++) {
            int bits = ((payload[offset] & 0xff) << 24)
                    | ((payload[offset + 1] & 0xff) << 16)
                    | ((payload[offset + 2] & 0xff) << 8)
                    | (payload[offset + 3] & 0xff);

            values[i] = Float.intBitsToFloat(bits);
            offset += Float.BYTES;
        }

        return values;
    }

    public static double[] decodeDoubleArrayValue(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_DOUBLE_ARRAY_CODE) {
            return null;
        }

        int length = payload[1] & 0xff;

        if (payload.length != 2 + (length * Double.BYTES)) {
            return null;
        }

        double[] values = new double[length];
        int offset = 2;

        for (int i = 0; i < length; i++) {
            long bits = ((long) (payload[offset] & 0xff) << 56)
                    | ((long) (payload[offset + 1] & 0xff) << 48)
                    | ((long) (payload[offset + 2] & 0xff) << 40)
                    | ((long) (payload[offset + 3] & 0xff) << 32)
                    | ((long) (payload[offset + 4] & 0xff) << 24)
                    | ((long) (payload[offset + 5] & 0xff) << 16)
                    | ((long) (payload[offset + 6] & 0xff) << 8)
                    | ((long) (payload[offset + 7] & 0xff));

            values[i] = Double.longBitsToDouble(bits);
            offset += Double.BYTES;
        }

        return values;
    }


    public static byte[] decodeRawByteArrayValue(byte[] payload) {
        if (payload == null) {
            return null;
        }

        if (payload.length == 1 && (payload[0] & 0xff) == GEODE_NULL_CODE) {
            return null;
        }

        if (payload.length == 0) {
            return Arrays.copyOf(payload, payload.length);
        }

        int first = payload[0] & 0xff;

        if (first == GEODE_STRING_ARRAY_CODE
                || first == GEODE_STRING_ARRAY_LIST_CODE
                || first == GEODE_HASH_MAP_CODE
                || first == GEODE_JAVA_SERIALIZED_CODE
                || first == GEODE_OBJECT_ARRAY_CODE
                || first == GEODE_BYTE_ARRAY_CODE
                || first == GEODE_BOOLEAN_ARRAY_CODE
                || first == GEODE_CHAR_ARRAY_CODE
                || first == GEODE_SHORT_ARRAY_CODE
                || first == GEODE_INT_ARRAY_CODE
                || first == GEODE_LONG_ARRAY_CODE
                || first == GEODE_FLOAT_ARRAY_CODE
                || first == GEODE_DOUBLE_ARRAY_CODE
                || first == GEODE_BOOLEAN_CODE
                || first == GEODE_CHARACTER_CODE
                || first == GEODE_BYTE_CODE
                || first == GEODE_SHORT_CODE
                || first == GEODE_INTEGER_CODE
                || first == GEODE_LONG_CODE
                || first == GEODE_FLOAT_CODE
                || first == GEODE_DOUBLE_CODE
                || first == GEODE_DATE_CODE
                || first == GEODE_BIG_INTEGER_CODE
                || first == GEODE_BIG_DECIMAL_CODE
                || first == GEODE_UUID_CODE
                || first == GEODE_ENUM_CODE
                || first == GEODE_PDX_INSTANCE_CODE
                || first == GEODE_DATA_SERIALIZABLE_CODE
                || first == GEODE_STRING_CODE) {
            return null;
        }

        if (isLikelyUtf8Text(payload)) {
            return null;
        }

        return Arrays.copyOf(payload, payload.length);
    }

    public static Boolean decodeBooleanValue(byte[] payload) {
        if (payload == null || payload.length != 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_BOOLEAN_CODE) {
            return null;
        }

        int value = payload[1] & 0xff;

        if (value == 0x00) {
            return Boolean.FALSE;
        }

        if (value == 0x01) {
            return Boolean.TRUE;
        }

        return null;
    }

    public static Character decodeCharacterValue(byte[] payload) {
        if (payload == null || payload.length != 3) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_CHARACTER_CODE) {
            return null;
        }

        int value = ((payload[1] & 0xff) << 8)
                | (payload[2] & 0xff);

        return (char) value;
    }

    public static Byte decodeByteValue(byte[] payload) {
        if (payload == null || payload.length != 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_BYTE_CODE) {
            return null;
        }

        return payload[1];
    }

    public static Short decodeShortValue(byte[] payload) {
        if (payload == null || payload.length != 3) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_SHORT_CODE) {
            return null;
        }

        int value = ((payload[1] & 0xff) << 8)
                | (payload[2] & 0xff);

        return (short) value;
    }

    public static Integer decodeIntegerValue(byte[] payload) {
        if (payload == null || payload.length != 5) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_INTEGER_CODE) {
            return null;
        }

        return ((payload[1] & 0xff) << 24)
                | ((payload[2] & 0xff) << 16)
                | ((payload[3] & 0xff) << 8)
                | (payload[4] & 0xff);
    }

    public static Long decodeLongValue(byte[] payload) {
        if (payload == null || payload.length != 9) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_LONG_CODE) {
            return null;
        }

        return ((long) (payload[1] & 0xff) << 56)
                | ((long) (payload[2] & 0xff) << 48)
                | ((long) (payload[3] & 0xff) << 40)
                | ((long) (payload[4] & 0xff) << 32)
                | ((long) (payload[5] & 0xff) << 24)
                | ((long) (payload[6] & 0xff) << 16)
                | ((long) (payload[7] & 0xff) << 8)
                | ((long) (payload[8] & 0xff));
    }

    public static Float decodeFloatValue(byte[] payload) {
        if (payload == null || payload.length != 5) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_FLOAT_CODE) {
            return null;
        }

        int bits = ((payload[1] & 0xff) << 24)
                | ((payload[2] & 0xff) << 16)
                | ((payload[3] & 0xff) << 8)
                | (payload[4] & 0xff);

        return Float.intBitsToFloat(bits);
    }

    public static Double decodeDoubleValue(byte[] payload) {
        if (payload == null || payload.length != 9) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_DOUBLE_CODE) {
            return null;
        }

        long bits = ((long) (payload[1] & 0xff) << 56)
                | ((long) (payload[2] & 0xff) << 48)
                | ((long) (payload[3] & 0xff) << 40)
                | ((long) (payload[4] & 0xff) << 32)
                | ((long) (payload[5] & 0xff) << 24)
                | ((long) (payload[6] & 0xff) << 16)
                | ((long) (payload[7] & 0xff) << 8)
                | ((long) (payload[8] & 0xff));

        return Double.longBitsToDouble(bits);
    }

    public static Date decodeDateValue(byte[] payload) {
        if (payload == null || payload.length != 9) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_DATE_CODE) {
            return null;
        }

        long epochMillis = ((long) (payload[1] & 0xff) << 56)
                | ((long) (payload[2] & 0xff) << 48)
                | ((long) (payload[3] & 0xff) << 40)
                | ((long) (payload[4] & 0xff) << 32)
                | ((long) (payload[5] & 0xff) << 24)
                | ((long) (payload[6] & 0xff) << 16)
                | ((long) (payload[7] & 0xff) << 8)
                | ((long) (payload[8] & 0xff));

        return new Date(epochMillis);
    }

    public static String decodeStringLikeValue(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        if (payload.length == 1 && (payload[0] & 0xff) == GEODE_NULL_CODE) {
            return null;
        }

        int first = payload[0] & 0xff;

        if (first == GEODE_HASH_MAP_CODE
                || first == GEODE_JAVA_SERIALIZED_CODE
                || first == GEODE_OBJECT_ARRAY_CODE
                || first == GEODE_PDX_INSTANCE_CODE
                || looksLikeJavaSerializationStream(payload)) {
            return null;
        }

        if (first == GEODE_STRING_CODE) {
            String decoded = decodeLengthPrefixedGeodeString(payload);
            if (decoded != null) {
                return decoded;
            }

            if (payload.length > 1) {
                String markerStripped = new String(
                        payload,
                        1,
                        payload.length - 1,
                        StandardCharsets.UTF_8
                )
                        .replace("\u0000", "")
                        .trim();

                return markerStripped.isBlank() ? null : markerStripped;
            }

            return null;
        }

        if (decodeStringArrayValue(payload) != null) {
            return null;
        }

        if (decodeStringArrayListValue(payload) != null) {
            return null;
        }

        if (decodeObjectArrayListValue(payload) != null) {
            return null;
        }

        if (decodeStringHashMapValue(payload) != null) {
            return null;
        }

        if (decodeStringObjectHashMapValue(payload) != null) {
            return null;
        }

        if (decodeOpaqueComplexMapValue(payload) != null) {
            return null;
        }

        if (decodeObjectArrayValue(payload) != null) {
            return null;
        }

        if (decodeJavaSerializedObjectValue(payload) != null) {
            return null;
        }

        if (decodeOpaqueStandaloneUtilityValue(payload) != null) {
            return null;
        }

        if (decodePdxInstanceValue(payload) != null) {
            return null;
        }

        if (decodeByteArrayValue(payload) != null) {
            return null;
        }

        if (decodeIntArrayValue(payload) != null) {
            return null;
        }

        if (decodeBooleanArrayValue(payload) != null) {
            return null;
        }

        if (decodeCharArrayValue(payload) != null) {
            return null;
        }

        if (decodeShortArrayValue(payload) != null) {
            return null;
        }

        if (decodeLongArrayValue(payload) != null) {
            return null;
        }

        if (decodeFloatArrayValue(payload) != null) {
            return null;
        }

        if (decodeDoubleArrayValue(payload) != null) {
            return null;
        }

        if (decodeBooleanValue(payload) != null) {
            return null;
        }

        if (decodeCharacterValue(payload) != null) {
            return null;
        }

        if (decodeByteValue(payload) != null) {
            return null;
        }

        if (decodeShortValue(payload) != null) {
            return null;
        }

        if (decodeIntegerValue(payload) != null) {
            return null;
        }

        if (decodeLongValue(payload) != null) {
            return null;
        }

        if (decodeFloatValue(payload) != null) {
            return null;
        }

        if (decodeDoubleValue(payload) != null) {
            return null;
        }

        if (decodeDateValue(payload) != null) {
            return null;
        }

        if (payload.length == 1 && isLikelyGeodeToken(payload[0] & 0xff)) {
            return null;
        }

        String text = new String(payload, StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .trim();

        return text.isBlank() ? null : text;
    }

    private static String decodeLengthPrefixedGeodeString(byte[] payload) {
        DecodedString decoded = decodeLengthPrefixedGeodeStringAt(payload, 0);
        return decoded == null ? null : decoded.value();
    }

    private static DecodedString decodeLengthPrefixedGeodeStringAt(byte[] payload, int offset) {
        if (payload == null || offset < 0 || offset >= payload.length) {
            return null;
        }

        if ((payload[offset] & 0xff) != GEODE_STRING_CODE) {
            return null;
        }

        if (payload.length < offset + 3) {
            return null;
        }

        int length = ((payload[offset + 1] & 0xff) << 8)
                | (payload[offset + 2] & 0xff);

        int start = offset + 3;
        int end = start + length;

        if (length < 0 || end > payload.length) {
            return null;
        }

        String value = new String(payload, start, length, StandardCharsets.UTF_8);
        return new DecodedString(value, end);
    }

    private static LinkedHashMap<String, String> tryDeserializeStringStringMap(byte[] payload) {
        try {
            Object rawValue = GeodeSerialization.deserializeObject(payload);

            if (!(rawValue instanceof Map<?, ?> rawMap)) {
                return null;
            }

            if (!isStringStringMap(rawMap)) {
                return null;
            }

            return toStringStringLinkedHashMap(rawMap);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeJavaSerializationStream(byte[] payload) {
        return looksLikeJavaSerializationStream(payload, 0, payload == null ? 0 : payload.length);
    }

    private static boolean looksLikeJavaSerializationStream(byte[] payload, int offset, int length) {
        return payload != null
                && offset >= 0
                && length >= 4
                && payload.length >= offset + length
                && (payload[offset] & 0xff) == 0xac
                && (payload[offset + 1] & 0xff) == 0xed
                && (payload[offset + 2] & 0xff) == 0x00
                && (payload[offset + 3] & 0xff) == 0x05;
    }

    private static String extractJavaSerializedClassName(byte[] serializedValue) {
        /*
         * We intentionally do not deserialize here.
         *
         * Integration-test and customer POJO classes may exist on the Geode
         * client classpath but not inside the shim container. Loading the object
         * with ObjectInputStream would require the shim to have the POJO class.
         *
         * For this compatibility layer, the shim only needs to preserve the raw
         * ObjectOutputStream bytes. The className is diagnostic metadata for the
         * Couchbase envelope, so best-effort stream parsing is sufficient.
         */
        if (!looksLikeJavaSerializationStream(serializedValue)) {
            return "unknown";
        }

        for (int i = 4; i < serializedValue.length - 3; i++) {
            /*
             * TC_CLASSDESC = 0x72
             * Followed by a two-byte modified UTF length and the class name.
             */
            if ((serializedValue[i] & 0xff) != 0x72) {
                continue;
            }

            int length = ((serializedValue[i + 1] & 0xff) << 8)
                    | (serializedValue[i + 2] & 0xff);

            int start = i + 3;
            int end = start + length;

            if (length <= 0 || end > serializedValue.length) {
                continue;
            }

            String candidate = new String(
                    serializedValue,
                    start,
                    length,
                    java.nio.charset.StandardCharsets.UTF_8
            );

            if (candidate.indexOf('.') > 0 && candidate.indexOf(' ') < 0) {
                return candidate;
            }
        }

        return "unknown";
    }
    private static Object deserializeJavaObjectAfterMarker(byte[] payload) {
        if (payload == null || payload.length <= 1) {
            return null;
        }

        return deserializeJavaObject(payload, 1, payload.length - 1);
    }

    private static Object deserializeJavaObject(byte[] payload, int offset, int length) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(payload, offset, length))) {
            return in.readObject();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // The structured/queryable supported-value rules (recursive over nested Map/Object[]/List plus
    // the JDK scalar extras) and the deep defensive copy both live in NestedValueSupport, so the
    // decode gate here and the wire re-encode gate in GemResponseWriter can never drift apart.
    private static boolean isSupportedStringObjectMap(Map<?, ?> value) {
        return NestedValueSupport.isSupportedStringObjectMap(value);
    }

    private static LinkedHashMap<String, Object> toStringObjectLinkedHashMap(Map<?, ?> value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            out.put(
                    key == null ? null : String.valueOf(key),
                    NestedValueSupport.copyValue(entry.getValue())
            );
        }

        return out;
    }

    private static boolean isStringStringMap(Map<?, ?> value) {
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object mapValue = entry.getValue();

            if (key != null && !(key instanceof String)) {
                return false;
            }

            if (mapValue != null && !(mapValue instanceof String)) {
                return false;
            }
        }

        return true;
    }

    private static LinkedHashMap<String, String> toStringStringLinkedHashMap(Map<?, ?> value) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object mapValue = entry.getValue();

            out.put(
                    key == null ? null : String.valueOf(key),
                    mapValue == null ? null : String.valueOf(mapValue)
            );
        }

        return out;
    }

    private static boolean isLikelyUtf8Text(byte[] payload) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        String text;

        try {
            text = decoder.decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            return false;
        }

        if (text.isBlank()) {
            return false;
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (Character.isISOControl(c)
                    && c != '\t'
                    && c != '\n'
                    && c != '\r') {
                return false;
            }
        }

        return true;
    }

    private static boolean isLikelyGeodeToken(int value) {
        return value >= 0x00 && value <= 0x7f;
    }

    private record DecodedString(String value, int nextOffset) {
    }
}