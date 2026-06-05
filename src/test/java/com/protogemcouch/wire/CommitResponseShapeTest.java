package com.protogemcouch.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-shape checks for the transaction COMMIT/ROLLBACK replies. The COMMIT reply carries a
 * zero-region TXCommitMessage object; its full round-trip through Geode's own deserializer is
 * proven by TxCommitProbe (CHECK_BUILT) against a real server. Here we lock the message framing and
 * the per-commit TXId.uniqId patch.
 */
class CommitResponseShapeTest {

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    @Test
    void commitResponseIsResponseTypeWithOneObjectPartAndPatchedUniqId() {
        int txId = 0x2a;
        byte[] msg = GemResponseWriter.buildCommitResponse(txId);

        assertEquals(MessageTypes.RESPONSE, readInt(msg, 0), "messageType = RESPONSE");
        assertEquals(1, readInt(msg, 8), "one part");
        assertEquals(txId, readInt(msg, 12), "header txId");

        int partLen = readInt(msg, 17);
        assertEquals(1, msg[21], "part is an object (isObject=1)");
        assertEquals(241, partLen, "zero-region TXCommitMessage object length");

        // Object: 01 6e (DS_FIXED_ID_BYTE, dsfid 110) + int processorId + int TXId.uniqId.
        assertEquals(0x01, msg[22] & 0xff);
        assertEquals(0x6e, msg[23] & 0xff);
        assertEquals(txId, readInt(msg, 22 + 6), "TXId.uniqId patched to the transaction id");
    }

    @Test
    void rollbackResponseIsAPlainReplyAck() {
        int txId = 11;
        byte[] msg = GemResponseWriter.buildRollbackResponse(txId);

        assertEquals(MessageTypes.REPLY, readInt(msg, 0), "messageType = REPLY");
        assertEquals(txId, readInt(msg, 12), "header txId");
        assertTrue(readInt(msg, 8) >= 1, "at least one part");
    }
}
