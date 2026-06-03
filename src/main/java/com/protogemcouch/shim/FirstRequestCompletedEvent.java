package com.protogemcouch.shim;

/**
 * Pipeline user event fired once a connection has completed its handshake and produced its first
 * fully-decoded request. {@link FirstRequestDeadlineHandler} listens for it to cancel the
 * first-request deadline.
 */
public enum FirstRequestCompletedEvent {
    INSTANCE
}
