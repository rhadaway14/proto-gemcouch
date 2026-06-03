package com.protogemcouch.shim;

import java.net.SocketAddress;

/**
 * Callback invoked when {@link FirstRequestDeadlineHandler} closes a connection that failed to
 * complete its first request within the deadline. Kept as a small interface so the handler can be
 * unit-tested without static logging/metrics state.
 */
@FunctionalInterface
public interface FirstRequestTimeoutListener {

    FirstRequestTimeoutListener NO_OP = remote -> { };

    void onFirstRequestTimeout(SocketAddress remote);
}
