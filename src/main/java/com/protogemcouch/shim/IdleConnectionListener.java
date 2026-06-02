package com.protogemcouch.shim;

import java.net.SocketAddress;

/**
 * Callback invoked when {@link IdleConnectionHandler} closes a connection for being idle. Keeping
 * this as a small interface lets the handler be unit-tested without static logging/metrics state.
 */
@FunctionalInterface
public interface IdleConnectionListener {

    IdleConnectionListener NO_OP = remote -> { };

    void onIdleClose(SocketAddress remote);
}
