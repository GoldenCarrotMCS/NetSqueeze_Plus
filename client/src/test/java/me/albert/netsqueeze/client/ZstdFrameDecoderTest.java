package me.albert.netsqueeze.client;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ZstdFrameDecoderTest {
    @Test
    void decodesZstdPayloadWithPacketLength() {
        byte[] packet = "large-packet-payload".repeat(8192).getBytes(StandardCharsets.UTF_8);
        byte[] raw = concat(varInt(packet.length), packet);
        byte[] compressed = Zstd.compress(raw, 1);
        byte[] decoded = ZstdFrameDecoder.decode(compressed);
        assertArrayEquals(raw, decoded);
    }

    @Test
    void rejectsOversizedWindowBeforeNativeDecompression() {
        // Content is only one byte, but the declared window requests one GiB.
        byte[] frame = {0x28, (byte)0xb5, 0x2f, (byte)0xfd, (byte)0x80, (byte)(20 << 3), 1, 0, 0, 0};
        var error = assertThrows(io.netty.handler.codec.CorruptedFrameException.class,
            () -> ZstdFrameDecoder.decode(frame));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("window"));
    }

    @Test
    void rejectsConcatenatedFramesAndMissingBlockBodies() {
        byte[] compressed = Zstd.compress(new byte[]{1, 2, 3}, 1);
        assertThrows(io.netty.handler.codec.CorruptedFrameException.class,
            () -> ZstdFrameDecoder.decode(concat(compressed, compressed)));
        byte[] headerOnly = {0x28, (byte)0xb5, 0x2f, (byte)0xfd, 0x20, 1};
        assertThrows(io.netty.handler.codec.CorruptedFrameException.class,
            () -> ZstdFrameDecoder.decode(headerOnly));
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] compressed = Zstd.compress(new byte[]{1, 2, 3}, 1);
        byte[] withTail = concat(compressed, new byte[]{9});
        assertThrows(RuntimeException.class, () -> ZstdFrameDecoder.decode(withTail));
    }

    @Test
    void rejectsContentSizeBomb() {
        byte[] compressed = Zstd.compress(new byte[8 * 1024 * 1024 + 6], 1);
        assertThrows(io.netty.handler.codec.CorruptedFrameException.class,
            () -> ZstdFrameDecoder.decode(compressed));
    }

    @Test
    void systemPropertyCanDisableCapabilityBeforeNegotiation() {
        String before = System.getProperty("netsqueeze.zstd.disable");
        try {
            System.setProperty("netsqueeze.zstd.disable", "true");
            assertFalse(ZstdSupport.available());
        } finally {
            if (before == null) System.clearProperty("netsqueeze.zstd.disable");
            else System.setProperty("netsqueeze.zstd.disable", before);
        }
    }

    private static byte[] varInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int part = value & 127;
            value >>>= 7;
            out.write(value == 0 ? part : part | 128);
        } while (value != 0);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(first); out.writeBytes(second);
        return out.toByteArray();
    }
}
