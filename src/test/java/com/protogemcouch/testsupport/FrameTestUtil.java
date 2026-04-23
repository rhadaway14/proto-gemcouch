package com.protogemcouch.testsupport;

import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class FrameTestUtil {

    private FrameTestUtil() {
    }

    public static GemFrame mockFrame(int messageType, GemPart... parts) {
        GemFrame frame = mock(GemFrame.class);
        when(frame.getMessageType()).thenReturn(messageType);
        when(frame.getNumberOfParts()).thenReturn(parts.length);
        when(frame.getTransactionId()).thenReturn(-1);
        when(frame.getParts()).thenReturn(List.of(parts));
        return frame;
    }

    public static GemPart part(byte[] payload) {
        return new GemPart(payload.length, (byte) 0x00, payload);
    }

    public static GemPart stringPart(String value) {
        return part(value.getBytes());
    }

    public static GemPart intPart(int value) {
        byte[] bytes = new byte[] {
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
        return new GemPart(bytes.length, (byte) 0x00, bytes);
    }

    public static GemPart objectPart(Object value) {
        byte[] bytes = GeodeSerialization.serializeObject(value);
        return new GemPart(bytes.length, (byte) 0x01, bytes);
    }
}