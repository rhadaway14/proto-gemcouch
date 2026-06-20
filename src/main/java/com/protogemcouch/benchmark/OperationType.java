package com.protogemcouch.benchmark;

public enum OperationType {
    GET,
    PUT,
    REMOVE,
    CONTAINS_KEY,
    GET_ALL,
    PUT_ALL,
    SIZE,
    KEY_SET,
    // Full-surface ops — exercise the query engine, transaction registry/commit, PDX registry, and the
    // in-transaction getEntry path under sustained concurrent load (the point of a full-surface soak).
    QUERY,
    TRANSACTION,
    PDX_PUT,
    GET_ENTRY
}
