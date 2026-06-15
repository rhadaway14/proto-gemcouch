package com.protogemcouch.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit formatter always prefixes the {@code audit=true} marker (so events are filterable even
 * when the audit logger is merged into the main stream) and otherwise uses the standard key=value
 * shape.
 */
class AuditLogTest {

    @Test
    void prependsAuditMarkerBeforeEventFields() {
        String line = AuditLog.format("connection_rejected", "reason", "max_connections_exceeded", "remote", "1.2.3.4:55");
        assertEquals("event=connection_rejected audit=true reason=max_connections_exceeded remote=1.2.3.4:55", line);
    }

    @Test
    void markerPresentEvenWithNoFields() {
        assertEquals("event=tls_handshake_rejected audit=true", AuditLog.format("tls_handshake_rejected"));
    }

    @Test
    void usesTheDedicatedAuditLoggerName() {
        assertEquals("protogemcouch.audit", AuditLog.LOGGER_NAME);
        // Sanity: emitting does not throw.
        AuditLog.event("malformed_frame", "reason", "oversized", "remote", "10.0.0.1:9");
        assertTrue(true);
    }
}
