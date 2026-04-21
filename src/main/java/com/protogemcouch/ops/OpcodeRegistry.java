package com.protogemcouch.ops;

import java.util.HashMap;
import java.util.Map;

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
}