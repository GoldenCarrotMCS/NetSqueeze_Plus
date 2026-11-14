package me.albert.netsqueeze.stream

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import org.junit.jupiter.api.Test
import kotlin.test.*

class ZstdFrameTest {
    @Test
    fun `large packet encodes as independent zstd frame with packet length`() {
        assertTrue(ZstdFrame.available(), "CI must load packaged ZSTD natives")
        val packet = ByteArray(128 * 1024) { (it % 29).toByte() }
        val input = Unpooled.wrappedBuffer(packet)
        val frame = try { ZstdFrame.encode(UnpooledByteBufAllocator.DEFAULT, input, 1) }
        finally { input.release() }
        assertNotNull(frame)
        try {
            val bodyLength = readVarInt(frame)
            assertEquals(bodyLength, frame.readableBytes())
            assertEquals(ZstdFrame.MARKER.toInt(), frame.readUnsignedByte().toInt())
            val compressed = ByteArray(frame.readableBytes())
            frame.readBytes(compressed)
            val raw = ZstdFrame.decode(compressed)
            val rawBuf = Unpooled.wrappedBuffer(raw)
            try {
                assertEquals(packet.size, readVarInt(rawBuf))
                val decoded = ByteArray(rawBuf.readableBytes())
                rawBuf.readBytes(decoded)
                assertContentEquals(packet, decoded)
            } finally { rawBuf.release() }
        } finally { frame.release() }
    }

    @Test
    fun `high entropy packet can safely fall back without changing deflate state`() {
        assertTrue(ZstdFrame.available(), "CI must load packaged ZSTD natives")
        val packet = ByteArray(64 * 1024).also { java.util.Random(91).nextBytes(it) }
        val input = Unpooled.wrappedBuffer(packet)
        try {
            assertNull(ZstdFrame.encode(UnpooledByteBufAllocator.DEFAULT, input, 1))
            assertEquals(0, input.readerIndex())
            assertEquals(packet.size, input.readableBytes())
        } finally { input.release() }
    }

    private fun readVarInt(buf: ByteBuf): Int {
        var value = 0
        for (i in 0 until 5) {
            val b = buf.readUnsignedByte().toInt()
            value = value or ((b and 0x7f) shl (i * 7))
            if (b and 0x80 == 0) return value
        }
        fail("invalid varint")
    }
}
