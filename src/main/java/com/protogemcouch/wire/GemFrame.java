package com.protogemcouch.wire;

import java.util.ArrayList;
import java.util.List;

public class GemFrame {
    private int messageType;
    private int payloadLength;
    private int numberOfParts;
    private int transactionId;
    private byte flags;
    private final List<GemPart> parts = new ArrayList<>();

    public int getMessageType() {
        return messageType;
    }

    public void setMessageType(int messageType) {
        this.messageType = messageType;
    }

    public int getPayloadLength() {
        return payloadLength;
    }

    public void setPayloadLength(int payloadLength) {
        this.payloadLength = payloadLength;
    }

    public int getNumberOfParts() {
        return numberOfParts;
    }

    public void setNumberOfParts(int numberOfParts) {
        this.numberOfParts = numberOfParts;
    }

    public int getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(int transactionId) {
        this.transactionId = transactionId;
    }

    public byte getFlags() {
        return flags;
    }

    public void setFlags(byte flags) {
        this.flags = flags;
    }

    public List<GemPart> getParts() {
        return parts;
    }

    public void addPart(GemPart part) {
        parts.add(part);
    }
}