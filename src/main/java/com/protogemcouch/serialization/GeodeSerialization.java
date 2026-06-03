package com.protogemcouch.serialization;

import org.apache.geode.DataSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class GeodeSerialization {

    private static final int GEODE_STRING_CODE = 0x57;
    private static final int GEODE_BOOLEAN_CODE = 0x35;

    private GeodeSerialization() {
    }

    public static byte[] serializeObject(Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                DataSerializer.writeObject(value, out);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to Geode-serialize object", e);
        }
    }

    public static Object deserializeObject(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            return DataSerializer.readObject(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to Geode-deserialize object", e);
        }
    }

    public static byte[] serializeString(String value) {
        if (value == null) {
            return new byte[0];
        }

        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);

        if (utf8.length > 0xFFFF) {
            throw new IllegalArgumentException("String is too large for validated Geode string encoding");
        }

        byte[] out = new byte[1 + 2 + utf8.length];
        out[0] = (byte) GEODE_STRING_CODE;
        out[1] = (byte) ((utf8.length >>> 8) & 0xff);
        out[2] = (byte) (utf8.length & 0xff);
        System.arraycopy(utf8, 0, out, 3, utf8.length);
        return out;
    }

    public static String deserializeString(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        if ((payload[0] & 0xff) == GEODE_STRING_CODE && payload.length >= 3) {
            int length = ((payload[1] & 0xff) << 8)
                    | (payload[2] & 0xff);

            if (payload.length >= 3 + length) {
                return new String(payload, 3, length, StandardCharsets.UTF_8);
            }
        }

        Object value = deserializeObject(payload);
        return value == null ? null : String.valueOf(value);
    }

    public static byte[] serializeBoolean(boolean value) {
        return new byte[] {
                (byte) GEODE_BOOLEAN_CODE,
                (byte) (value ? 0x01 : 0x00)
        };
    }

    /**
     * GET_ALL key decoding path.
     *
     * Avoid DataSerializer as the primary path here. In the shaded container on
     * newer Java versions, org.apache.geode.DataSerializer can fail during static
     * initialization through Geode's LogService/StackLocator path.
     *
     * The validated GET_ALL request path uses string-like keys encoded with:
     *
     *   0x57
     *   two-byte unsigned UTF-8 length
     *   UTF-8 bytes
     */
    public static List<String> deserializeGetAllKeys(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return List.of();
        }

        List<String> keys = scanGeodeStrings(payload);

        if (!keys.isEmpty()) {
            return keys;
        }

        /*
         * Fallback for unit tests or older local paths where payloads may be
         * directly DataSerializer-compatible.
         */
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            Object obj = DataSerializer.readObject(in);

            if (obj instanceof Collection<?> collection) {
                List<String> out = new ArrayList<>();
                for (Object item : collection) {
                    if (item != null) {
                        out.add(String.valueOf(item));
                    }
                }
                return out;
            }

            if (obj instanceof Object[] array) {
                List<String> out = new ArrayList<>();
                for (Object item : array) {
                    if (item != null) {
                        out.add(String.valueOf(item));
                    }
                }
                return out;
            }

            if (obj != null) {
                return List.of(String.valueOf(obj));
            }

            return List.of();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to deserialize getAll keys", t);
        }
    }

    private static List<String> scanGeodeStrings(byte[] payload) {
        List<String> out = new ArrayList<>();

        int i = 0;
        while (i < payload.length) {
            int marker = payload[i] & 0xff;

            if (marker != GEODE_STRING_CODE) {
                i++;
                continue;
            }

            if (i + 3 > payload.length) {
                break;
            }

            int length = ((payload[i + 1] & 0xff) << 8)
                    | (payload[i + 2] & 0xff);

            int start = i + 3;
            int end = start + length;

            if (end > payload.length) {
                i++;
                continue;
            }

            String value = new String(payload, start, length, StandardCharsets.UTF_8);

            if (!value.isBlank() && looksLikeApplicationKey(value)) {
                out.add(value);
            }

            i = end;
        }

        return out;
    }

    private static boolean looksLikeApplicationKey(String value) {
        if (value.startsWith("java.")
                || value.startsWith("org.")
                || value.startsWith("com.")
                || value.contains("/")
                || value.contains("\u0000")) {
            return false;
        }

        return true;
    }
}