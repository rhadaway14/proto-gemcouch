package com.protogemcouch.subscription;

import java.util.function.Consumer;

/**
 * Default {@link EventBackplane}: does nothing. The shim runs single-instance — local feeds only — with
 * no cross-replica broadcast and no external dependency. This is the behavior when no backplane is
 * configured, so a single-replica (or eventing-not-needed) deployment pays nothing.
 */
public final class NoOpEventBackplane implements EventBackplane {

    @Override
    public void publish(RemoteEvent event) {
        // single-instance: nothing to broadcast
    }

    @Override
    public void subscribe(Consumer<RemoteEvent> handler) {
        // single-instance: no remote events ever arrive
    }

    @Override
    public void close() {
        // nothing to release
    }
}
