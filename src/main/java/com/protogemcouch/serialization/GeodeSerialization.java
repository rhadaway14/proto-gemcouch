package com.protogemcouch.serialization;

import org.apache.geode.DataSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class GeodeSerialization {

    private GeodeSerialization() {
    }

    public static byte[] serializeString(String value) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            DataSerializer.writeString(value, dos);
            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to Geode-serialize string: " + value, e);
        }
    }

    public static byte[] serializeBoolean(boolean value) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            DataSerializer.writeObject(Boolean.valueOf(value), dos);
            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to Geode-serialize boolean: " + value, e);
        }
    }

    public static byte[] serializeObject(Object value) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            DataSerializer.writeObject(value, dos);
            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to Geode-serialize object: " + value, e);
        }
    }

    public static String deserializeString(byte[] bytes) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            DataInputStream dis = new DataInputStream(bais);
            return DataSerializer.readString(dis);
        } catch (Exception e) {
            throw new RuntimeException("Failed to Geode-deserialize string", e);
        }
    }

    public static Object deserializeObject(byte[] bytes) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            DataInputStream dis = new DataInputStream(bais);
            return DataSerializer.readObject(dis);
        } catch (Exception e) {
            throw new RuntimeException("Failed to Geode-deserialize object", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> deserializeGetAllKeys(byte[] payload) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(payload);
            DataInputStream dis = new DataInputStream(bais);
            Object obj = DataSerializer.readObject(dis);

            List<String> keys = new ArrayList<>();

            if (obj instanceof Object[] arr) {
                for (Object o : arr) {
                    keys.add(String.valueOf(o));
                }
                return keys;
            }

            if (obj instanceof Iterable<?> iterable) {
                for (Object o : iterable) {
                    keys.add(String.valueOf(o));
                }
                return keys;
            }

            if (obj != null) {
                keys.add(String.valueOf(obj));
            }

            return keys;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize getAll keys", e);
        }
    }
}