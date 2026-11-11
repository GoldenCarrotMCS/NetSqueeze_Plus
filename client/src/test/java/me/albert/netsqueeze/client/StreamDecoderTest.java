package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamDecoderTest {
    @Test
    void acceptsMaximumPositiveVarInt() {
        check(new byte[]{-1, -1, -1, -1, 7}, Integer.MAX_VALUE);
    }

    @Test
    void rejectsVarIntOverflowAndNegativeLength() {
        for (int last : new int[]{8, 15, 16, 127, 128, 255}) {
            ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{-1, -1, -1, -1, (byte) last});
            try {
                assertThrows(CorruptedFrameException.class, () -> StreamDecoder.readVarInt(buf));
            } finally { buf.release(); }
        }
    }

    @Test
    void incompleteVarIntReturnsSentinelForCallerToRestoreIndex() {
        check(new byte[]{(byte) 0x80}, -1);
    }

    private static void check(byte[] bytes, int expected) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try { assertEquals(expected, StreamDecoder.readVarInt(buf)); }
        finally { buf.release(); }
    }
}
