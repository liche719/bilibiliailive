package com.bilibili.ailive.liveplatform.bilibili;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

final class BilibiliPacketCodec {

    static final int HEADER_LENGTH = 16;
    static final int OP_HEARTBEAT = 2;
    static final int OP_HEARTBEAT_REPLY = 3;
    static final int OP_MESSAGE = 5;
    static final int OP_AUTH = 7;
    static final int OP_AUTH_REPLY = 8;

    private BilibiliPacketCodec() {
    }

    static byte[] encode(int operation, byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        return ByteBuffer.allocate(HEADER_LENGTH + payload.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(HEADER_LENGTH + payload.length)
                .putShort((short) HEADER_LENGTH)
                .putShort((short) 0)
                .putInt(operation)
                .putInt(1)
                .put(payload)
                .array();
    }

    static List<BilibiliPacket> decode(byte[] bytes) {
        List<BilibiliPacket> packets = new ArrayList<>();
        decodeInto(bytes, packets);
        return List.copyOf(packets);
    }

    private static void decodeInto(byte[] bytes, List<BilibiliPacket> packets) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        while (buffer.remaining() >= HEADER_LENGTH) {
            int packetStart = buffer.position();
            int packetLength = buffer.getInt();
            int headerLength = Short.toUnsignedInt(buffer.getShort());
            int version = Short.toUnsignedInt(buffer.getShort());
            int operation = buffer.getInt();
            buffer.getInt();
            if (packetLength < headerLength || headerLength < HEADER_LENGTH || packetLength > buffer.limit() - packetStart) {
                throw new BilibiliOpenLiveException("Invalid Bilibili WebSocket packet length");
            }
            buffer.position(packetStart + headerLength);
            byte[] body = new byte[packetLength - headerLength];
            buffer.get(body);
            if (version == 2) {
                decodeInto(inflate(body), packets);
            } else if (version == 0 || version == 1) {
                packets.add(new BilibiliPacket(operation, body));
            } else {
                throw new BilibiliOpenLiveException("Unsupported Bilibili WebSocket packet version: " + version);
            }
            buffer.position(packetStart + packetLength);
        }
        if (buffer.hasRemaining()) {
            throw new BilibiliOpenLiveException("Incomplete Bilibili WebSocket packet");
        }
    }

    private static byte[] inflate(byte[] compressed) {
        try (
                InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed));
                ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            inflater.transferTo(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BilibiliOpenLiveException("Unable to inflate Bilibili WebSocket packet", exception);
        }
    }
}
