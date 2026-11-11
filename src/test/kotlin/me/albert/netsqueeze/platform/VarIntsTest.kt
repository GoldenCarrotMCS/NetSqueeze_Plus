package me.albert.netsqueeze.platform

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Test
import kotlin.test.*

class VarIntsTest {
    @Test
    fun `independent varints preserve NMS wire encoding`() {
        for (value in listOf(0, 1, 127, 128, 16383, 16384, Int.MAX_VALUE)) {
            val buf = Unpooled.buffer()
            try {
                VarInts.write(buf, value)
                assertEquals(VarInts.getByteSize(value), buf.readableBytes())
                var decoded = 0
                var shift = 0
                while (buf.isReadable) {
                    decoded = decoded or ((buf.readUnsignedByte().toInt() and 127) shl shift)
                    shift += 7
                }
                assertEquals(value, decoded)
            } finally { buf.release() }
        }
    }
}
