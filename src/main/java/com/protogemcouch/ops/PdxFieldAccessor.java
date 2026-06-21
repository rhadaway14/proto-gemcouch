package com.protogemcouch.ops;

import com.protogemcouch.query.OqlQuery;
import org.apache.geode.internal.tcp.ByteBufferInputStream.ByteSource;
import org.apache.geode.pdx.FieldType;
import org.apache.geode.pdx.internal.PdxField;
import org.apache.geode.pdx.internal.PdxReaderImpl;
import org.apache.geode.pdx.internal.PdxType;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a named field from a stored PDX instance using Geode's own PdxReaderImpl (so the PDX
 * binary layout / offset handling is exactly correct). A PDX instance is framed as
 * {@code 0x5d <int length> <int typeId> <fieldData>}; the typeId maps to a {@link PdxType} the shim
 * kept from the client's type registration.
 */
final class PdxFieldAccessor {

    private PdxFieldAccessor() {
    }

    /** Read {@code field} from a PDX instance, or {@code null} when it cannot be resolved. */
    static Object read(byte[] instance, PdxTypeRegistry registry, String field) {
        if (instance == null || instance.length < 9 || (instance[0] & 0xff) != 0x5d) {
            return null;
        }
        int length = readInt(instance, 1);
        int typeId = readInt(instance, 5);
        int dataStart = 9;
        if (length < 0 || dataStart + length > instance.length) {
            return null;
        }

        PdxType type = registry.getPdxType(typeId);
        if (type == null) {
            return null;
        }
        PdxField pdxField = type.getPdxField(field);
        if (pdxField == null) {
            return null;
        }

        byte[] fieldData = Arrays.copyOfRange(instance, dataStart, dataStart + length);
        try {
            PdxReaderImpl reader = new PdxReaderImpl(
                    type, new DataInputStream(new ByteArrayInputStream(fieldData)), fieldData.length);
            return readScalar(reader, pdxField);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * Resolve a (possibly nested) field path against a PDX instance, returning the leaf scalar or
     * {@link OqlQuery#ABSENT} when it cannot be resolved. A single-element path reads a scalar field
     * (as {@link #read}); a longer path descends into a nested OBJECT field. Nested PDX objects are
     * navigated by their <em>raw bytes</em> (which carry the nested typeId + {@code 0x5d} framing) and
     * the shim's own type registry — Geode's own deserialization can't, since the nested type lives in
     * this registry, not Geode's. Nested maps (PDX field holding a Geode {@code HashMap}) are navigated
     * by key.
     */
    static Object resolvePath(byte[] instance, PdxTypeRegistry registry, List<String> path) {
        if (path == null || path.isEmpty()) {
            return OqlQuery.ABSENT;
        }
        if (instance == null || instance.length < 9 || (instance[0] & 0xff) != 0x5d) {
            return OqlQuery.ABSENT;
        }
        int length = readInt(instance, 1);
        int typeId = readInt(instance, 5);
        int dataStart = 9;
        if (length < 0 || dataStart + length > instance.length) {
            return OqlQuery.ABSENT;
        }
        PdxType type = registry.getPdxType(typeId);
        if (type == null) {
            return OqlQuery.ABSENT;
        }
        PdxField pdxField = type.getPdxField(path.get(0));
        if (pdxField == null) {
            return OqlQuery.ABSENT;
        }

        byte[] fieldData = Arrays.copyOfRange(instance, dataStart, dataStart + length);
        try {
            PdxReaderImpl reader = new PdxReaderImpl(
                    type, new DataInputStream(new ByteArrayInputStream(fieldData)), fieldData.length);
            if (path.size() == 1) {
                // A scalar-array leaf returns the whole list (so `<literal> IN <array>` can test it);
                // any other leaf returns its scalar value (null for OBJECT/object-array/byte[]).
                List<Object> array = readScalarArray(reader, pdxField);
                if (array != null) {
                    return array;
                }
                Object scalar = readScalar(reader, pdxField);
                return scalar == null ? OqlQuery.ABSENT : scalar;
            }
            // Descend (path has more segments): into a nested OBJECT field, or index into a scalar array.
            if (pdxField.getFieldType() == FieldType.OBJECT) {
                byte[] nested = rawFieldBytes(reader, pdxField);
                if (nested != null && nested.length >= 9 && (nested[0] & 0xff) == 0x5d) {
                    return resolvePath(nested, registry, path.subList(1, path.size())); // nested PDX: recurse
                }
                return OqlQuery.ABSENT; // nested non-PDX object: not navigable in this build
            }
            List<Object> array = readScalarArray(reader, pdxField);
            if (array != null) {
                return navigateRest(array, path.subList(1, path.size())); // e.g. tags[0]
            }
            return OqlQuery.ABSENT; // can't navigate into a plain scalar / unsupported array
        } catch (Exception | LinkageError e) {
            return OqlQuery.ABSENT;
        }
    }

    /** Apply the remaining path segments (indexes / keys) via the shared map/array navigator. */
    private static Object navigateRest(Object current, List<String> rest) {
        Object value = current;
        for (String segment : rest) {
            if (value == null) {
                return OqlQuery.ABSENT;
            }
            value = OqlQuery.navigateMember(value, segment);
            if (value == OqlQuery.ABSENT) {
                return OqlQuery.ABSENT;
            }
        }
        return value;
    }

    /** Read a PDX <em>scalar</em> array field into a boxed list, or {@code null} for non-array / unsupported types. */
    private static List<Object> readScalarArray(PdxReaderImpl reader, PdxField field) {
        switch (field.getFieldType()) {
            case STRING_ARRAY:
                return boxed(reader.readStringArray(field));
            case INT_ARRAY: {
                int[] a = reader.readIntArray(field);
                if (a == null) return null;
                List<Object> out = new java.util.ArrayList<>(a.length);
                for (int v : a) out.add(v);
                return out;
            }
            case LONG_ARRAY: {
                long[] a = reader.readLongArray(field);
                if (a == null) return null;
                List<Object> out = new java.util.ArrayList<>(a.length);
                for (long v : a) out.add(v);
                return out;
            }
            case SHORT_ARRAY: {
                short[] a = reader.readShortArray(field);
                if (a == null) return null;
                List<Object> out = new java.util.ArrayList<>(a.length);
                for (short v : a) out.add(v);
                return out;
            }
            case DOUBLE_ARRAY: {
                double[] a = reader.readDoubleArray(field);
                if (a == null) return null;
                List<Object> out = new java.util.ArrayList<>(a.length);
                for (double v : a) out.add(v);
                return out;
            }
            case FLOAT_ARRAY: {
                float[] a = reader.readFloatArray(field);
                if (a == null) return null;
                List<Object> out = new java.util.ArrayList<>(a.length);
                for (float v : a) out.add(v);
                return out;
            }
            case BOOLEAN_ARRAY: {
                boolean[] a = reader.readBooleanArray(field);
                if (a == null) return null;
                List<Object> out = new java.util.ArrayList<>(a.length);
                for (boolean v : a) out.add(v);
                return out;
            }
            case CHAR_ARRAY: {
                char[] a = reader.readCharArray(field);
                if (a == null) return null;
                List<Object> out = new java.util.ArrayList<>(a.length);
                for (char v : a) out.add(String.valueOf(v));
                return out;
            }
            default:
                return null; // BYTE_ARRAY (binary), OBJECT_ARRAY, ARRAY_OF_BYTE_ARRAYS: not queryable here
        }
    }

    private static List<Object> boxed(Object[] array) {
        if (array == null) {
            return null;
        }
        return new java.util.ArrayList<>(Arrays.asList(array));
    }

    /** The raw serialized bytes of {@code field} (the protected {@code getRaw(PdxField)}, via reflection). */
    private static byte[] rawFieldBytes(PdxReaderImpl reader, PdxField field) {
        try {
            Method getRaw = PdxReaderImpl.class.getDeclaredMethod("getRaw", PdxField.class);
            getRaw.setAccessible(true);
            Object source = getRaw.invoke(reader, field);
            if (!(source instanceof ByteSource byteSource)) {
                return null;
            }
            byte[] bytes = new byte[byteSource.remaining()];
            byteSource.get(bytes);
            return bytes;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read every scalar field of a stored PDX instance into a name→value map (insertion-ordered), for
     * building a queryable sidecar. OBJECT/array fields are skipped (the same fields {@link #read} can't
     * resolve), and any unreadable field is skipped. Returns an empty map when the type is not known or
     * the instance is malformed — in which case the document gets no sidecar and is filtered the slow
     * (correct) way.
     */
    static Map<String, Object> readScalarFields(byte[] instance, PdxTypeRegistry registry) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (instance == null || instance.length < 9 || (instance[0] & 0xff) != 0x5d) {
            return fields;
        }
        int length = readInt(instance, 1);
        int typeId = readInt(instance, 5);
        int dataStart = 9;
        if (length < 0 || dataStart + length > instance.length) {
            return fields;
        }
        PdxType type = registry.getPdxType(typeId);
        if (type == null) {
            return fields;
        }

        byte[] fieldData = Arrays.copyOfRange(instance, dataStart, dataStart + length);
        try {
            for (PdxField pdxField : type.getFields()) {
                PdxReaderImpl reader = new PdxReaderImpl(
                        type, new DataInputStream(new ByteArrayInputStream(fieldData)), fieldData.length);
                Object scalar = readScalar(reader, pdxField);
                if (scalar != null) {
                    fields.put(pdxField.getFieldName(), scalar);
                }
            }
        } catch (Exception | LinkageError e) {
            return new LinkedHashMap<>(); // best-effort: no sidecar on any failure
        }
        return fields;
    }

    private static Object readScalar(PdxReaderImpl reader, PdxField field) {
        switch (field.getFieldType()) {
            case STRING: return reader.readString(field);
            case INT: return reader.readInt(field);
            case LONG: return reader.readLong(field);
            case SHORT: return reader.readShort(field);
            case BYTE: return reader.readByte(field);
            case BOOLEAN: return reader.readBoolean(field);
            case CHAR: return reader.readChar(field);
            case FLOAT: return reader.readFloat(field);
            case DOUBLE: return reader.readDouble(field);
            case DATE: return reader.readDate(field);
            // OBJECT / arrays are not queryable in this build (would need full object deserialization).
            default: return null;
        }
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xff) << 24) | ((b[offset + 1] & 0xff) << 16)
                | ((b[offset + 2] & 0xff) << 8) | (b[offset + 3] & 0xff);
    }
}
