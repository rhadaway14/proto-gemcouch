package com.protogemcouch.wire;

import java.net.SocketAddress;

/**
 * Callback invoked when {@link GemFrameDecoder} rejects a malformed or oversized frame.
 *
 * <p>Keeping this as a small interface lets the {@code wire} package stay free of any dependency on
 * logging or metrics; the server layer wires it to structured logging and the metrics registry.
 */
@FunctionalInterface
public interface MalformedFrameListener {

    /** A listener that does nothing, used as a safe default. */
    MalformedFrameListener NO_OP = (reason, remote, offendingValue) -> { };

    /**
     * @param reason         short machine-readable reason code (e.g. {@code "payload_length_out_of_bounds"})
     * @param remote         the remote peer address, if known (may be {@code null})
     * @param offendingValue the length/count that triggered rejection, or {@code -1} if not applicable
     */
    void onMalformedFrame(String reason, SocketAddress remote, long offendingValue);
}
