package com.protogemcouch.observability;

import com.protogemcouch.wire.MessageTypes;

public final class OperationNames {

    private OperationNames() {
    }

    public static String nameOf(int opcode) {
        return switch (opcode) {
            case MessageTypes.GET -> "GET";
            case MessageTypes.PING -> "PING";
            case MessageTypes.PUT -> "PUT";
            case MessageTypes.REMOVE -> "REMOVE";
            case MessageTypes.CONTROL -> "CONTROL";
            case MessageTypes.CONTAINS_KEY -> "CONTAINS";
            case MessageTypes.KEY_SET -> "KEY_SET";
            case MessageTypes.PUT_ALL -> "PUT_ALL";
            case MessageTypes.GET_CLIENT_PARTITION_ATTRIBUTES -> "GET_CLIENT_PARTITION_ATTRIBUTES";
            case MessageTypes.SIZE -> "SIZE";
            case MessageTypes.GET_ALL_70 -> "GET_ALL";
            default -> "UNKNOWN";
        };
    }
}