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
            // Descend (path has more segments): into a nested OBJECT field, an object-array of nested
            // PDX, or index into a scalar array.
            if (pdxField.getFieldType() == FieldType.OBJECT) {
                byte[] nested = rawFieldBytes(reader, pdxField);
                if (nested != null && nested.length >= 9 && (nested[0] & 0xff) == 0x5d) {
                    return resolvePath(nested, registry, path.subList(1, path.size())); // nested PDX: recurse
                }
                return OqlQuery.ABSENT; // nested non-PDX object: not navigable in this build
            }
            if (pdxField.getFieldType() == FieldType.OBJECT_ARRAY) {
                List<Object> elements = readObjectArrayElements(reader, pdxField);
                if (elements == null) {
                    return OqlQuery.ABSENT;
                }
                return navigateObjectArray(elements, registry, path.subList(1, path.size())); // e.g. addresses[0].zip
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

    /**
     * Navigate the remaining path through a PDX <em>object-array</em> field: the first remaining segment
     * indexes the array (e.g. {@code addresses[0]}), then any further segments resolve against the
     * selected element. A nested-PDX element ({@code 0x5d}-framed raw bytes) is descended via
     * {@link #resolvePath} with the shim's own {@link PdxTypeRegistry} (PDX is self-describing — no user
     * class needed); a {@code null} element, an out-of-range index, or a bare element with no trailing
     * field resolves to {@link OqlQuery#ABSENT}.
     */
    private static Object navigateObjectArray(List<Object> elements, PdxTypeRegistry registry, List<String> rest) {
        if (rest.isEmpty()) {
            return OqlQuery.ABSENT; // `r.addresses` with no index/field: a bare object-array is not comparable
        }
        Object element = OqlQuery.navigateMember(elements, rest.get(0)); // index into the element list
        if (element == OqlQuery.ABSENT || element == null) {
            return OqlQuery.ABSENT;
        }
        List<String> after = rest.subList(1, rest.size());
        if (element instanceof byte[] pdxBytes) {
            if (after.isEmpty()) {
                return OqlQuery.ABSENT; // `r.addresses[0]` selects a whole object: not a comparable leaf
            }
            return resolvePath(pdxBytes, registry, after); // `addresses[0].zip` (and deeper) recurse into the element
        }
        return navigateRest(element, after); // non-PDX element (shouldn't occur from our parser): best-effort
    }

    /**
     * Read a PDX <em>object-array</em> field's raw bytes ({@code DataSerializer.writeObjectArray} form)
     * into a list whose entries are each nested-PDX element's raw {@code 0x5d}-framed bytes (or
     * {@code null} for a null element). Returns {@code null} when the field cannot be read.
     */
    private static List<Object> readObjectArrayElements(PdxReaderImpl reader, PdxField field) {
        byte[] raw = rawFieldBytes(reader, field);
        if (raw == null) {
            return null;
        }
        return parseObjectArrayElements(raw);
    }

    /**
     * Walk the {@code writeObjectArray} byte form — a {@code writeArrayLength} prefix, an optional
     * component-type header ({@code DSCODE.CLASS 0x2b} + class-name string, e.g.
     * {@code org.apache.geode.pdx.PdxInstance}), then each element via {@code writeObject} — collecting
     * each <em>nested-PDX</em> element ({@code DSCODE.PDX 0x5d}, self-framed
     * {@code 0x5d <int len> <int typeId> <data>}) as its raw byte slice and each {@code DSCODE.NULL 0x29}
     * element as {@code null}. The homogeneous-PDX (optionally null-bearing) object-array is the
     * queryable case; on any other element DSCODE — whose length we cannot compute to keep walking —
     * collection stops and later indices resolve to {@link OqlQuery#ABSENT}. Returns an empty list for a
     * null/empty array, or {@code null} when the length prefix is malformed.
     */
    /**
     * Skip the optional component-type header at {@code pos}: {@code DSCODE.CLASS (0x2b)} followed by the
     * class name as a Geode string ({@code DSCODE.STRING 0x57} = unsigned-short length, or
     * {@code DSCODE.HUGE_STRING 0x58} = int length), or a lone {@code DSCODE.NULL (0x29)} for a null
     * component class. Returns the position of the first element, the unchanged {@code pos} when no
     * header is present (the byte is already an element DSCODE), or {@code -1} when a header is present
     * but its encoding is not recognized (so the caller degrades safely).
     */
    private static int skipComponentType(byte[] raw, int pos) {
        if (pos >= raw.length) {
            return pos;
        }
        int marker = raw[pos] & 0xff;
        if (marker == 0x29) { // DSCODE.NULL component class
            return pos + 1;
        }
        if (marker != 0x2b) { // no component header — already at the first element
            return pos;
        }
        int strPos = pos + 1;
        if (strPos >= raw.length) {
            return -1;
        }
        int strMarker = raw[strPos] & 0xff;
        if (strMarker == 0x57) { // DSCODE.STRING: unsigned-short length
            if (strPos + 3 > raw.length) return -1;
            int len = ((raw[strPos + 1] & 0xff) << 8) | (raw[strPos + 2] & 0xff);
            int next = strPos + 3 + len;
            return next <= raw.length ? next : -1;
        }
        if (strMarker == 0x58) { // DSCODE.HUGE_STRING: int length (defensive — class names are short)
            if (strPos + 5 > raw.length) return -1;
            int len = readInt(raw, strPos + 1);
            int next = strPos + 5 + len;
            return (len >= 0 && next <= raw.length) ? next : -1;
        }
        return -1; // unrecognized class-name encoding
    }

    static List<Object> parseObjectArrayElements(byte[] raw) {
        if (raw == null || raw.length < 1) {
            return null;
        }
        int pos = 0;
        int code = raw[pos++] & 0xff;
        int count;
        if (code == 0xff) {
            return new java.util.ArrayList<>(); // null array (-1): nothing to navigate
        } else if (code <= 0xfc) {
            count = code; // single-byte length
        } else if (code == 0xfe) { // SHORT_ARRAY_LEN
            if (pos + 2 > raw.length) return null;
            count = ((raw[pos] & 0xff) << 8) | (raw[pos + 1] & 0xff);
            pos += 2;
        } else if (code == 0xfd) { // INT_ARRAY_LEN
            if (pos + 4 > raw.length) return null;
            count = readInt(raw, pos);
            pos += 4;
        } else {
            return null;
        }

        // Optional component-type header: writeObjectArray records the array's component class before
        // the elements (DSCODE.CLASS + class-name string, or DSCODE.NULL for an unknown component).
        pos = skipComponentType(raw, pos);
        if (pos < 0) {
            return new java.util.ArrayList<>(); // unrecognized component encoding: nothing safely navigable
        }

        List<Object> elements = new java.util.ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            if (pos >= raw.length) {
                break; // truncated: stop safely
            }
            int dscode = raw[pos] & 0xff;
            if (dscode == 0x5d) { // DSCODE.PDX: 0x5d <int len> <int typeId> <data>, total = 9 + len
                if (pos + 9 > raw.length) break;
                int len = readInt(raw, pos + 1);
                if (len < 0 || pos + 9 + len > raw.length) break;
                elements.add(Arrays.copyOfRange(raw, pos, pos + 9 + len));
                pos += 9 + len;
            } else if (dscode == 0x29) { // DSCODE.NULL: a single marker byte
                elements.add(null);
                pos += 1;
            } else {
                break; // unknown element type: cannot compute its length to continue
            }
        }
        return elements;
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
