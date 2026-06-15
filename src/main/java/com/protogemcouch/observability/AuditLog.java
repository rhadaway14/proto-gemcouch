package com.protogemcouch.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated audit log stream for security-relevant events: rejected connections (max-connections cap),
 * slowloris / first-request timeouts, malformed/oversized frames, and TLS/mTLS handshake rejections.
 *
 * <p>Events are emitted via a distinct logger ({@link #LOGGER_NAME}) at WARN and carry a consistent
 * {@code audit=true} marker plus the remote peer and a reason, in the same {@code key=value} format as
 * {@link StructuredLog}. The distinct logger name lets operators route the audit trail to a separate
 * sink (file / SIEM) — or filter it by name or by the {@code audit=true} marker — independently of the
 * shim's operational logs, without it getting lost in routine output.
 */
public final class AuditLog {

    /** Logger name for the audit stream; route this separately in production logging config. */
    public static final String LOGGER_NAME = "protogemcouch.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);

    private AuditLog() {
    }

    /** Emit a security audit event (logged at WARN on the dedicated audit logger). */
    public static void event(String event, Object... kvPairs) {
        AUDIT.warn(format(event, kvPairs));
    }

    /** Format the audit line: the {@code audit=true} marker first, then the event's key/value pairs. */
    static String format(String event, Object... kvPairs) {
        Object[] withMarker = new Object[kvPairs.length + 2];
        withMarker[0] = "audit";
        withMarker[1] = Boolean.TRUE;
        System.arraycopy(kvPairs, 0, withMarker, 2, kvPairs.length);
        return StructuredLog.event(event, withMarker);
    }
}
