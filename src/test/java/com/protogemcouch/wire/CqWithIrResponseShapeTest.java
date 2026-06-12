package com.protogemcouch.wire;

import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.ByteUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Golden-wire test for the CQ {@code executeWithInitialResults} (EXECUTECQ_WITH_IR) reply, byte-for-byte
 * against a real Geode 1.15.1 server capture (tools/CqCapture WITH_IR=1): a chunked query-style RESPONSE
 * whose part[0] is the {@code Struct{key,value}} CollectionType and part[1] is an ObjectPartList of the
 * matching entries (each a nested {@code Struct}). The empty result set uses the query empty-result form.
 */
class CqWithIrResponseShapeTest {

    // Captured chunked RESPONSE for two matching entries: k1=v1, k2=v2 (txId -1).
    private static final byte[] GOLDEN_TWO_ENTRIES = ByteUtils.hex(
            "0000000100000002ffffffff000000e901000000b00101c52b5700146a6176612e7574696c2e436f6c6c656374696f6e"
                    + "01c42b5700236f72672e6170616368652e67656f64652e63616368652e71756572792e537472756374025700036b65"
                    + "7957000576616c7565022b57002d6f72672e6170616368652e67656f64652e63616368652e71756572792e74797065"
                    + "732e4f626a6563745479706501c32b5700106a6176612e6c616e672e4f626a65637401c32b5700106a6176612e6c61"
                    + "6e672e4f626a6563740000002f01011900000000020001190000000002005700026b3100570002763100011900000000"
                    + "02005700026b32005700027632");

    // Captured chunked RESPONSE for an empty result set (txId -1): same CollectionType, query empty form.
    private static final byte[] GOLDEN_EMPTY = ByteUtils.hex(
            "0000000100000002ffffffff000000d001000000b00101c52b5700146a6176612e7574696c2e436f6c6c656374696f6e"
                    + "01c42b5700236f72672e6170616368652e67656f64652e63616368652e71756572792e537472756374025700036b65"
                    + "7957000576616c7565022b57002d6f72672e6170616368652e67656f64652e63616368652e71756572792e74797065"
                    + "732e4f626a6563745479706501c32b5700106a6176612e6c616e672e4f626a65637401c32b5700106a6176612e6c61"
                    + "6e672e4f626a656374000000160134002b5700106a6176612e6c616e672e4f626a656374");

    @Test
    void twoMatchingEntriesMatchTheCapturedBytes() {
        byte[] reply = GemResponseWriter.buildExecuteCqWithIrReply(-1, List.of(
                Map.entry("k1", StoredValue.stringValue("v1")),
                Map.entry("k2", StoredValue.stringValue("v2"))));
        assertArrayEquals(GOLDEN_TWO_ENTRIES, reply);
    }

    @Test
    void emptyResultSetMatchesTheCapturedBytes() {
        assertArrayEquals(GOLDEN_EMPTY, GemResponseWriter.buildExecuteCqWithIrReply(-1, List.of()));
    }
}
