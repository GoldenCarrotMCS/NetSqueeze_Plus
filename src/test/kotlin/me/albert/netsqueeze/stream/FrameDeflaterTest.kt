package me.albert.netsqueeze.stream

import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

class FrameDeflaterTest {

    @Test
    fun `multiple packets round trip through one frame`() {
        val packets = listOf(
            ByteArray(257) { (it * 31).toByte() },
            "重复数据用于验证跨包压缩字典".toByteArray(),
            ByteArray(4096) { if (it % 17 == 0) 0x7f else 0x2a }
        )
        val expected = ByteArrayOutputStream()
        packets.forEach { packet ->
            writeVarInt(expected, packet.size)
            expected.write(packet)
        }

        val deflater = FrameDeflater(UnpooledByteBufAllocator.DEFAULT)
        try {
            packets.forEach { packet ->
                val input = Unpooled.wrappedBuffer(packet)
                try {
                    deflater.feed(input)
                } finally {
                    input.release()
                }
            }

            val frame = assertNotNull(deflater.finish())
            try {
                val bodyLength = readVarInt(frame)
                assertEquals(bodyLength, frame.readableBytes())
                assertEquals(0, frame.readUnsignedByte().toInt())
                val compressed = ByteArray(frame.readableBytes())
                frame.readBytes(compressed)
                assertContentEquals(expected.toByteArray(), inflate(compressed))
            } finally {
                frame.release()
            }
        } finally {
            deflater.close()
        }
    }

    private fun inflate(compressed: ByteArray): ByteArray {
        val inflater = Inflater(true)
        val output = ByteArrayOutputStream()
        try {
            inflater.setInput(compressed)
            val buffer = ByteArray(1024)
            while (true) {
                val produced = inflater.inflate(buffer)
                if (produced > 0) {
                    output.write(buffer, 0, produced)
                    continue
                }
                if (inflater.needsInput()) break
                fail("Inflater did not reach the SYNC_FLUSH boundary")
            }
            assertEquals(0, inflater.remaining)
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun writeVarInt(output: ByteArrayOutputStream, value: Int) {
        var current = value
        while (current and 0x7f.inv() != 0) {
            output.write(current and 0x7f or 0x80)
            current = current ushr 7
        }
        output.write(current)
    }

    private fun readVarInt(buf: io.netty.buffer.ByteBuf): Int {
        var value = 0
        for (i in 0 until 5) {
            val current = buf.readUnsignedByte().toInt()
            value = value or ((current and 0x7f) shl (i * 7))
            if (current and 0x80 == 0) return value
        }
        fail("invalid frame length VarInt")
    }
}
