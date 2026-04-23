package com.protogemcouch.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentKeyUtilTest {

    @Test
    void docId_builds_expected_format() {
        String result = DocumentKeyUtil.docId("/helloWorld", "my-key");
        assertEquals("/helloWorld::my-key", result);
    }

    @Test
    void docId_preserves_embedded_delimiters_in_key() {
        String result = DocumentKeyUtil.docId("/helloWorld", "a::b::c");
        assertEquals("/helloWorld::a::b::c", result);
    }

    @Test
    void docId_handles_empty_key() {
        String result = DocumentKeyUtil.docId("/helloWorld", "");
        assertEquals("/helloWorld::", result);
    }

    @Test
    void docId_handles_empty_region() {
        String result = DocumentKeyUtil.docId("", "my-key");
        assertEquals("::my-key", result);
    }
}