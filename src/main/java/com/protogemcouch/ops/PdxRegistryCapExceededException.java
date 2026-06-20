package com.protogemcouch.ops;

/**
 * Thrown when a client tries to register a new PDX type or enum but the registry is already at its
 * operator-configured cap ({@code MAX_PDX_TYPES} / {@code MAX_PDX_ENUMS}). The cap bounds the in-memory
 * PDX registry against unbounded growth from a client registering a very large number of distinct
 * types; it is off by default (unlimited). The dispatch turns this into a clean error reply to the
 * client rather than letting the registry grow without bound.
 */
public class PdxRegistryCapExceededException extends RuntimeException {

    public PdxRegistryCapExceededException(String message) {
        super(message);
    }
}
