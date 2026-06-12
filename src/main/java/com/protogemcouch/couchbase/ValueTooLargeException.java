package com.protogemcouch.couchbase;

/**
 * Signals that a value's encoded document would exceed the configured maximum size
 * ({@code CB_MAX_VALUE_BYTES}, default Couchbase's 20 MiB ceiling). Thrown before any backend write,
 * so an oversized value never reaches Couchbase and never updates the region's keyset. It is unchecked
 * and propagates to the request dispatch loop, which surfaces it to the client as a
 * {@code ServerOperationException}; in a {@code putAll} batch it is recorded as a per-key failure.
 */
public class ValueTooLargeException extends RuntimeException {

    private final long actualBytes;
    private final long maxBytes;

    public ValueTooLargeException(long actualBytes, long maxBytes) {
        super("value of " + actualBytes + " bytes exceeds the configured maximum of " + maxBytes
                + " bytes (CB_MAX_VALUE_BYTES)");
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }

    public long actualBytes() {
        return actualBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
