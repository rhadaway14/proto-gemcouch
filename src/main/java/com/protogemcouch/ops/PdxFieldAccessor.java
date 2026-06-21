package com.protogemcouch.ops;

import org.apache.geode.pdx.internal.PdxField;
import org.apache.geode.pdx.internal.PdxReaderImpl;
import org.apache.geode.pdx.internal.PdxType;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
