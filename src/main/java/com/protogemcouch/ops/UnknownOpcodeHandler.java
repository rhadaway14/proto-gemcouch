package com.protogemcouch.ops;

import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;

public class UnknownOpcodeHandler implements OperationHandler {

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        System.out.println("UNKNOWN FRAME TYPE: " + frame.getMessageType());

        for (int i = 0; i < frame.getParts().size(); i++) {
            GemPart p = frame.getParts().get(i);
            System.out.println("unknown part[" + i + "] len=" + p.getLength()
                    + " type=" + String.format("0x%02x", p.getTypeCode())
                    + " hex=" + ByteBufUtil.hexDump(p.getPayload())
                    + " text=" + ByteUtils.bytesToString(p.getPayload()));
        }
    }
}