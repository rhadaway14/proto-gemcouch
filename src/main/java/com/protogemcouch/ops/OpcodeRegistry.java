package com.protogemcouch.ops;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class OpcodeRegistry {

    private final Map<Integer, OperationHandler> handlers = new HashMap<>();

    public void register(int opcode, OperationHandler handler) {
        handlers.put(opcode, handler);
    }

    public OperationHandler get(int opcode) {
        return handlers.get(opcode);
    }

    public boolean contains(int opcode) {
        return handlers.containsKey(opcode);
    }

    /** The set of request opcodes this registry handles. Used by the golden-wire coverage test to
     *  guarantee every handled opcode has a byte-exact reply fixture (or an explicit no-reply exemption). */
    public Set<Integer> opcodes() {
        return Set.copyOf(handlers.keySet());
    }
}