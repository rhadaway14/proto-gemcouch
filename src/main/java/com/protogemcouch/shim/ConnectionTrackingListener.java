package com.protogemcouch.shim;

import java.net.SocketAddress;

/**
 * Callbacks for connection lifecycle events emitted by {@link ConnectionTrackingHandler}. Keeping
 * this as a small interface lets the handler be unit-tested without static logging/metrics state.
 */
public interface ConnectionTrackingListener {

    ConnectionTrackingListener NO_OP = new ConnectionTrackingListener() { };

    default void onConnectionOpened(SocketAddress remote, int activeConnections) { }

    default void onConnectionClosed(SocketAddress remote) { }

    default void onConnectionRejected(SocketAddress remote, int maxConnections) { }
}
