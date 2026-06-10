package com.protogemcouch.wire;

public final class MessageTypes {

    private MessageTypes() {
    }

    // Request opcodes
    public static final int GET = 0;
    public static final int PING = 5;
    public static final int PUT = 7;
    public static final int REMOVE = 9;
    public static final int QUERY = 34;
    public static final int QUERY_DATA_ERROR = 35;
    /*
     * Parameterized OQL query (org.apache.geode.internal.cache.tier.MessageType.QUERY_WITH_PARAMETERS).
     * part[0] = OQL string (with $1..$N placeholders), part[1] = int bind-parameter count,
     * part[2..] = each bind value as a Geode-serialized object. The response is the same chunked
     * CumulativeNonDistinctResults as a plain QUERY.
     */
    public static final int QUERY_WITH_PARAMETERS = 80;
    public static final int CLEAR_REGION = 36;
    public static final int CONTROL = 18;
    public static final int CONTAINS_KEY = 38;
    public static final int KEY_SET = 40;
    public static final int INVALIDATE = 83;
    public static final int GET_ENTRY = 89;
    public static final int KEY_SET_DATA_ERROR = 41;
    public static final int PUT_ALL = 56;
    public static final int GET_CLIENT_PARTITION_ATTRIBUTES = 73;
    public static final int SIZE = 81;
    public static final int SIZE_ERROR = 82;

    /*
     * Client transaction opcodes (org.apache.geode.internal.cache.tier.MessageType). Transactional
     * ops (PUT/GET/REMOVE/...) carry the tx id (>= 0) in the message header; COMMIT/ROLLBACK end the
     * tx. The COMMIT reply is a RESPONSE carrying a serialized TXCommitMessage object; ROLLBACK is a
     * plain REPLY ack.
     */
    public static final int COMMIT = 85;
    public static final int COMMIT_ERROR = 86;
    public static final int ROLLBACK = 87;
    public static final int TX_SYNCHRONIZATION = 90;

    /*
     * Client subscription / register-interest opcodes (request side, on the control connection), and
     * the server->client notification opcodes pushed down the feed connection. See
     * docs/SUBSCRIPTIONS.md. The feed/control connections are identified by their first
     * communication-mode byte (101 PrimaryServerToClient feed, 107 ClientToServerForQueue control),
     * handled in RawShimServer's handshake dispatch.
     */
    public static final int REGISTER_INTEREST = 20;
    public static final int UNREGISTER_INTEREST = 22;
    public static final int REGISTER_INTEREST_LIST = 24;
    public static final int UNREGISTER_INTEREST_LIST = 25;
    // Server->client notifications (pushed down the feed):
    public static final int LOCAL_INVALIDATE = 15;
    public static final int LOCAL_DESTROY = 16;
    public static final int LOCAL_CREATE = 27;
    public static final int LOCAL_UPDATE = 28;
    public static final int CLIENT_MARKER = 54;

    /*
     * PDX registry request discovered from Geode client PdxInstanceFactory.
     *
     * Geode stack trace:
     *   GetPDXIdForTypeOp.execute(...)
     *
     * Shim discovery log:
     *   opcode=93
     *   parts=1
     *   txId=-1
     *   part[0] contains serialized org.apache.geode.pdx.internal.PdxType
     */
    public static final int GET_PDX_ID_FOR_TYPE = 93;
    public static final int GET_PDX_ID_FOR_ENUM = 97;

    public static final int GET_ALL_70 = 100;

    // Response opcodes
    public static final int RESPONSE = 1;
    public static final int REPLY = 6;
    /** Chunked response from the primary server (used by the register-interest KEYS_VALUES reply). */
    public static final int RESPONSE_FROM_PRIMARY = 32;

    /**
     * Geode server-side exception response. The client deserializes part 0 into a Throwable and
     * raises a ServerOperationException, rather than seeing an abrupt connection close.
     *
     * Value matches org.apache.geode.internal.cache.tier.MessageType.EXCEPTION (2). Note: 22 is
     * UNREGISTER_INTEREST, not EXCEPTION.
     */
    public static final int EXCEPTION = 2;

    // Contains modes
    public static final int CONTAINS_MODE_KEY = 0;
    public static final int CONTAINS_MODE_VALUE_FOR_KEY = 1;
    public static final int CONTAINS_MODE_VALUE = 2;
}