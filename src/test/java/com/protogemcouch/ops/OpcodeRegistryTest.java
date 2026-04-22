package com.protogemcouch.ops;

import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpcodeRegistryTest {

    @Test
    void register_and_get_returns_same_handler() {
        OpcodeRegistry registry = new OpcodeRegistry();
        OperationHandler handler = new NoOpHandler();

        registry.register(7, handler);

        assertTrue(registry.contains(7));
        assertSame(handler, registry.get(7));
    }

    @Test
    void get_unknown_opcode_returns_null() {
        OpcodeRegistry registry = new OpcodeRegistry();
        assertNull(registry.get(999));
        assertFalse(registry.contains(999));
    }

    private static class NoOpHandler implements OperationHandler {
        @Override
        public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        }
    }
}