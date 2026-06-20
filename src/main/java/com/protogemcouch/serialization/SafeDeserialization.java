package com.protogemcouch.serialization;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

/**
 * Guards the Java-deserialization the shim performs on <em>untrusted client values</em> against
 * deserialization gadget attacks (CWE-502). The shim deserializes a client's Java-serialized value
 * only to <em>inspect</em> it (is it a {@code Map}? a {@code Collection}?) for queryability — but a
 * gadget chain executes during {@code readObject} regardless of how the result is used, so the
 * deserialization itself must be constrained.
 *
 * <p>Two layers:
 * <ul>
 *   <li>{@link #deserialize(byte[], int, int)} runs every shim-owned {@code ObjectInputStream} with a
 *       strict <b>allowlist</b> filter — only JDK container and scalar types
 *       ({@code java.lang} / {@code java.util} / {@code java.math} / {@code java.time}) are permitted;
 *       any application class (where gadget chains live) is rejected and the value is preserved
 *       opaquely instead. Depth/reference/array bounds also cap resource use.</li>
 *   <li>{@link #installProcessWideGadgetFilter()} sets a JVM-wide serialization filter (defense in
 *       depth) that blocks the well-known gadget packages for <em>any</em> {@code ObjectInputStream}
 *       in the process — including the one Geode's {@code DataSerializer} uses internally for a
 *       {@code SERIALIZABLE}-coded element — while still allowing legitimate classes so Geode's own
 *       serialization keeps working. It defers to an operator-provided {@code jdk.serialFilter}.</li>
 * </ul>
 */
public final class SafeDeserialization {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_REFERENCES = 10_000;
    private static final int MAX_ARRAY_LENGTH = 100_000;

    /** Only these package prefixes (plus primitives/arrays of them) may be deserialized by the shim. */
    private static final String[] ALLOWED_PREFIXES = {
            "java.lang.",   // String, the wrapper/Number types, Enum
            "java.util.",   // the collection/map/set types
            "java.math.",   // BigInteger, BigDecimal
            "java.time."    // Instant, LocalDate, LocalDateTime, … (+ their Ser proxy)
    };

    /** Gadget packages blocked process-wide; everything else stays allowed so Geode keeps working. */
    private static final String GADGET_BLOCKLIST =
            "maxdepth=64;maxrefs=200000;maxarray=1000000;"
            + "!org.apache.commons.collections.functors.*;"
            + "!org.apache.commons.collections4.functors.*;"
            + "!org.apache.commons.beanutils.*;"
            + "!org.codehaus.groovy.runtime.*;"
            + "!org.springframework.beans.factory.*;"
            + "!org.springframework.aop.*;"
            + "!com.sun.org.apache.xalan.*;"
            + "!org.apache.xalan.*;"
            + "!javax.management.*;"
            + "!java.rmi.server.*;"
            + "!sun.rmi.server.*;"
            + "!bsh.*;"
            + "!org.python.core.*;"
            + "!org.mozilla.javascript.*;"
            + "!clojure.*;"
            + "!com.mchange.v2.c3p0.*;"
            + "!org.jboss.interceptor.*;"
            + "*"; // allow everything not explicitly blocked (keeps Geode's legitimate deser working)

    private static final ObjectInputFilter ALLOWLIST_FILTER = SafeDeserialization::checkAllowlist;

    private SafeDeserialization() {
    }

    /**
     * Deserialize a Java-serialized payload under the shim's strict allowlist filter. Returns
     * {@code null} on any failure — a rejected class, malformed bytes, or a missing class — so callers
     * fall back to preserving the value opaquely.
     */
    public static Object deserialize(byte[] payload, int offset, int length) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(payload, offset, length))) {
            in.setObjectInputFilter(ALLOWLIST_FILTER);
            return in.readObject();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ObjectInputFilter.Status checkAllowlist(ObjectInputFilter.FilterInfo info) {
        if (info.depth() > MAX_DEPTH
                || info.references() > MAX_REFERENCES
                || info.arrayLength() > MAX_ARRAY_LENGTH) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> clazz = info.serialClass();
        if (clazz == null) {
            return ObjectInputFilter.Status.UNDECIDED; // non-class check (stream/array bounds already done)
        }
        while (clazz.isArray()) {
            clazz = clazz.getComponentType();
        }
        if (clazz.isPrimitive()) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        String name = clazz.getName();
        for (String prefix : ALLOWED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return ObjectInputFilter.Status.ALLOWED;
            }
        }
        return ObjectInputFilter.Status.REJECTED;
    }

    /**
     * Install a JVM-wide serialization filter that blocks known gadget packages, as defense in depth
     * for {@code ObjectInputStream}s the shim does not own (notably Geode's internal {@code DataSerializer}
     * deserialization of a {@code SERIALIZABLE} element). No-op if the operator already configured a
     * {@code jdk.serialFilter} or a process-wide filter is already set, so it never overrides an
     * operator policy. Call once, early in startup.
     */
    public static synchronized void installProcessWideGadgetFilter() {
        if (System.getProperty("jdk.serialFilter") != null) {
            return; // respect an operator-provided filter
        }
        if (ObjectInputFilter.Config.getSerialFilter() != null) {
            return; // already configured
        }
        ObjectInputFilter.Config.setSerialFilter(ObjectInputFilter.Config.createFilter(GADGET_BLOCKLIST));
    }
}
