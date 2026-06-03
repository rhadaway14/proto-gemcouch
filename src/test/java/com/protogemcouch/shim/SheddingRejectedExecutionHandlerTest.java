package com.protogemcouch.shim;

import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SheddingRejectedExecutionHandlerTest {

    @Test
    void recordsShedAndRejectsTheTask() {
        AtomicInteger shed = new AtomicInteger();
        SheddingRejectedExecutionHandler handler = new SheddingRejectedExecutionHandler(shed::incrementAndGet);

        assertThrows(RejectedExecutionException.class,
                () -> handler.rejected(() -> { }, null));
        assertEquals(1, shed.get(), "shedding a task should record the metric callback");
    }

    @Test
    void toleratesNullCallback() {
        SheddingRejectedExecutionHandler handler = new SheddingRejectedExecutionHandler(null);
        assertThrows(RejectedExecutionException.class, () -> handler.rejected(() -> { }, null));
    }
}
