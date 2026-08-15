package com.bilibili.ailive.liveplatform.bilibili;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BilibiliPacketCodecTest {

    @Test
    void encodesAndDecodesAnUncompressedPacket() {
        byte[] encoded = BilibiliPacketCodec.encode(
                BilibiliPacketCodec.OP_AUTH,
                "auth-body".getBytes(StandardCharsets.UTF_8)
        );

        List<BilibiliPacket> decoded = BilibiliPacketCodec.decode(encoded);

        assertEquals(1, decoded.size());
        assertEquals(BilibiliPacketCodec.OP_AUTH, decoded.getFirst().operation());
        assertArrayEquals("auth-body".getBytes(StandardCharsets.UTF_8), decoded.getFirst().body());
    }

    @Test
    void recursivelyDecodesMultiplePacketsInsideVersionTwoZlibPayload() throws Exception {
        byte[] first = BilibiliPacketCodec.encode(BilibiliPacketCodec.OP_MESSAGE, "one".getBytes(StandardCharsets.UTF_8));
        byte[] second = BilibiliPacketCodec.encode(BilibiliPacketCodec.OP_MESSAGE, "two".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream nested = new ByteArrayOutputStream();
        nested.write(first);
        nested.write(second);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(nested.toByteArray());
        }

        List<BilibiliPacket> decoded = BilibiliPacketCodec.decode(versionTwoPacket(compressed.toByteArray()));

        assertEquals(2, decoded.size());
        assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), decoded.get(0).body());
        assertArrayEquals("two".getBytes(StandardCharsets.UTF_8), decoded.get(1).body());
    }

    private static byte[] versionTwoPacket(byte[] body) {
        return ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH + body.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(BilibiliPacketCodec.HEADER_LENGTH + body.length)
                .putShort((short) BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) 2)
                .putInt(BilibiliPacketCodec.OP_MESSAGE)
                .putInt(1)
                .put(body)
                .array();
    }
}
