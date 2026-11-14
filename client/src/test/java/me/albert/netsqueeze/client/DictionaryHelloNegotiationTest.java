package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import static me.albert.netsqueeze.client.ProtocolFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryHelloNegotiationTest {
    @Test void dictionaryEnableAndFollowingFramesAreDecodedInSameReadAndRandomFragments() throws Exception {
        byte[] fixture = Files.readAllBytes(path("dictionary-direct.bin"));
        // Last vanilla frame; the test decoder replaces only Minecraft object decoding.
        byte[] wire = concat(new byte[]{1, 0x42}, fixture);
        List<byte[]> expected = records("dictionary-expected.bin");
        for (boolean fragmented : new boolean[]{false, true}) {
            EmbeddedChannel ch = ProtocolRoundTripTest.channel(false, NetSqueezeClient.ENABLE_DICT);
            try {
                if (fragmented) {
                    Random random = new Random(976);
                    for (int offset = 0; offset < wire.length;) {
                        int length = Math.min(wire.length - offset, 1 + random.nextInt(4096));
                        ch.writeInbound(Unpooled.wrappedBuffer(wire, offset, length)); offset += length;
                    }
                } else ch.writeInbound(Unpooled.wrappedBuffer(wire));
                for (byte[] packet : expected) {
                    ByteBuf actual = ch.readInbound(); assertNotNull(actual);
                    try {
                        byte[] bytes = new byte[actual.readableBytes()]; actual.readBytes(bytes);
                        assertArrayEquals(packet, bytes);
                    } finally { actual.release(); }
                }
                assertNull(ch.readInbound());
                assertNull(ch.pipeline().get(EnableDetector.NAME));
            } finally { ch.finishAndReleaseAll(); }
        }
    }
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length); return out;
    }
}
