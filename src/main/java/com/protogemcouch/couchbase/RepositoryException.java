package com.protogemcouch.couchbase;

/**
 * Signals an infrastructure-level failure while talking to the backing store (e.g. the backend is
 * unavailable, a timeout elapsed, authentication was rejected, or a stored document could not be
 * decoded).
 *
 * <p>This is deliberately distinct from a legitimate "not found" result: a miss is represented by a
 * {@code null}/{@code false}/empty return value, whereas a {@code RepositoryException} means the
 * answer is unknown because the operation failed. Callers must not treat a failure as an empty
 * result — doing so would let an outage masquerade as a cache that genuinely has no data.
 *
 * <p>It is unchecked so the {@link Repository} interface stays clean; it propagates up to the
 * request dispatch loop, which records it as an operation error.
 */
public class RepositoryException extends RuntimeException {

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
