package com.protogemcouch.wire;

public class GemPart {
    private final int length;
    private final byte typeCode;
    private final byte[] payload;

    public GemPart(int length, byte typeCode, byte[] payload) {
        this.length = length;
        this.typeCode = typeCode;
        this.payload = payload;
    }

    public int getLength() {
        return length;
    }

    public byte getTypeCode() {
        return typeCode;
    }

    public byte[] getPayload() {
        return payload;
    }
}