package me.albert.netsqueeze.protocol;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ZstdFrameHeaderTest {
    @Test void decodesContentSizeWidthsAndTwoByteBias() {
        assertEquals(1, ZstdFrameHeader.validate(header(0x20, 0, 1)).contentSize());
        assertEquals(256, ZstdFrameHeader.validate(header(0x60, 0, 0)).contentSize());
        assertEquals(65791, ZstdFrameHeader.validate(header(0x60, 0, 65535)).contentSize());
        assertEquals(100000, ZstdFrameHeader.validate(header(0xa0, 0, 100000)).contentSize());
        assertEquals(100000, ZstdFrameHeader.validate(header(0xe0, 0, 100000)).contentSize());
    }
    @Test void enforcesWindowBoundaryIncludingMantissa() {
        var exact = ZstdFrameHeader.validate(header(0x80, 13 << 3, ZstdFrameHeader.MAX_CONTENT));
        assertEquals(ZstdFrameHeader.MAX_WINDOW, exact.windowSize());
        assertEquals(ZstdFrameHeader.MAX_CONTENT, exact.contentSize());
        assertEquals(ZstdFrameHeader.MAX_WINDOW, ZstdFrameHeader.validate(header(0xa0, 0, ZstdFrameHeader.MAX_WINDOW)).windowSize());
        assertEquals(1, ZstdFrameHeader.validate(header(0x30, 0, 1)).contentSize()); // unused bit is ignored
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(header(0x80, (13 << 3) | 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(header(0x80, 255, 1)));
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(header(0xa0, 0, ZstdFrameHeader.MAX_WINDOW + 1)));
    }
    @Test void rejectsUnknownContentDictionariesReservedBitsAndSkippableFrames() {
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(header(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(header(0x21, 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(header(0x28, 0, 1)));
        byte[] skip = header(0x20, 0, 1); skip[0] = 0x50;
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(skip));
    }
    @Test void rejectsUnsignedOverflowTruncationAndZeroContent() {
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(header(0xe0, 0, -1)));
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(header(0xa0, 0, ZstdFrameHeader.MAX_CONTENT + 1L)));
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(header(0x20, 0, 0)));
        byte[] complete = header(0x80, 0, 1);
        for (int i = 0; i < complete.length; i++) {
            byte[] prefix = Arrays.copyOf(complete, i);
            assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(prefix));
        }
        assertThrows(IllegalArgumentException.class, () -> ZstdFrameHeader.validate(null));
    }
    private static byte[] header(int descriptor, int window, long storedSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x28, (byte)0xb5, 0x2f, (byte)0xfd, (byte)descriptor});
        if ((descriptor & 32) == 0) out.write(window);
        int flag = descriptor >>> 6;
        int bytes = flag == 0 ? ((descriptor & 32) == 0 ? 0 : 1) : 1 << flag;
        for (int i = 0; i < bytes; i++) out.write((int)(storedSize >>> (8 * i)) & 255);
        return out.toByteArray();
    }
}
