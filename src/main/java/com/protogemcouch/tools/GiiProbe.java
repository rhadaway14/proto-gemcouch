package com.protogemcouch.tools;

import com.protogemcouch.wire.GemResponseWriter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev probe: print the getAll VersionedObjectList payload the shim builds for a small known set, so it
 * can be diffed byte-for-byte against a captured KEYS_VALUES register-interest reply (which is a
 * VersionedObjectList <em>with</em> version tags). Helps derive the extra header/flags, object
 * wrapping, and version-tag section the GII reply needs.
 */
public final class GiiProbe {

    private GiiProbe() {
    }

    public static void main(String[] args) {
        List<String> keys = List.of("a", "b", "c");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("a", "1");
        values.put("b", "2");
        values.put("c", "3");

        byte[] msg = GemResponseWriter.buildGetAllChunkedResponse(1, keys, values);
        System.out.println("getAll chunked msg = " + hex(msg));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
