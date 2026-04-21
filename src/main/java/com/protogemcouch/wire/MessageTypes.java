package com.protogemcouch.wire;

public final class MessageTypes {

    private MessageTypes() {
    }

    // Request opcodes
    public static final int GET = 0;
    public static final int PING = 5;
    public static final int PUT = 7;
    public static final int REMOVE = 9;
    public static final int CONTROL = 18;
    public static final int CONTAINS_KEY = 38;
    public static final int KEY_SET = 40;
    public static final int KEY_SET_DATA_ERROR = 41;
    public static final int PUT_ALL = 56;
    public static final int GET_CLIENT_PARTITION_ATTRIBUTES = 73;
    public static final int SIZE = 81;
    public static final int SIZE_ERROR = 82;
    public static final int GET_ALL_70 = 100;

    // Response opcodes
    public static final int RESPONSE = 1;
    public static final int REPLY = 6;

    // Contains modes
    public static final int CONTAINS_MODE_KEY = 0;
    public static final int CONTAINS_MODE_VALUE_FOR_KEY = 1;
    public static final int CONTAINS_MODE_VALUE = 2;
}