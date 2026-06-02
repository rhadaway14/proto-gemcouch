package com.protogemcouch.couchbase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryExceptionTest {

    @Test
    void retainsMessageAndCause() {
        IllegalStateException cause = new IllegalStateException("backend down");
        RepositoryException ex = new RepositoryException("get failed for docId=x", cause);

        assertEquals("get failed for docId=x", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void isUncheckedSoItNeedsNoInterfaceChange() {
        assertTrue(RuntimeException.class.isAssignableFrom(RepositoryException.class));
    }
}
