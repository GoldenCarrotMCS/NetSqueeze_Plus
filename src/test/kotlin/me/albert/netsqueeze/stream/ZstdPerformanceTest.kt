package me.albert.netsqueeze.stream

import com.github.luben.zstd.ZstdCompressCtx
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import me.albert.netsqueeze.protocol.ZstdDictionary
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.*

/** Performance changes must remain byte-for-byte equivalent to the pre-optimization encoder. */
class ZstdPerformanceTest {
    @Test fun `skip bound preserves comparison cutoff exactly`() {
        assertFalse(ZstdFrame.dictionaryCanWin(0))
        assertFalse(ZstdFrame.dictionaryCanWin(95))
        assertTrue(ZstdFrame.dictionaryCanWin(96))
        assertTrue(ZstdFrame.dictionaryCanWin(97))
    }

    @Test fun `one context and reusable history match separate context reference`() {
        assertTrue(ZstdFrame.dictionaryAvailable())
        val block = ByteArray(48 * 1024).also { Random(792).nextBytes(it) }
        val repeated = block + block
        val edited = repeated.clone().also { it[90000] = (it[90000].toInt() xor 1).toByte() }
        val entropy = ByteArray(96 * 1024).also { Random(190).nextBytes(it) }
        val tinyOutput = ByteArray(96 * 1024) { 7 }
        val packets = listOf(repeated, repeated, edited, tinyOutput, tinyOutput, entropy, repeated, repeated)
        for (level in listOf(1, 3, 5)) {
            ZstdDictionary().use { actualHistory ->
                ZstdDictionary().use { expectedHistory ->
                    for ((index, packet) in packets.withIndex()) {
                        val expected = originalEncoding(packet, level, expectedHistory)
                        val input = Unpooled.wrappedBuffer(packet)
                        val actual = try { ZstdFrame.encode(UnpooledByteBufAllocator.DEFAULT, input, level, actualHistory) }
                        finally { input.release() }
                        try {
                            if (expected == null) assertNull(actual, "level=$level packet=$index")
                            else {
                                assertNotNull(actual)
                                val bytes = ByteArray(actual.readableBytes())
                                actual.getBytes(actual.readerIndex(), bytes)
                                assertContentEquals(expected, bytes, "level=$level packet=$index")
                            }
                            assertContentEquals(expectedHistory.content(), actualHistory.content())
                            assertContentEquals(expectedHistory.digest(), actualHistory.digest())
                        } finally { actual?.release() }
                    }
                }
            }
        }
    }

    @Test fun `very compressible large frames remain independent with established history`() {
        val packet = ByteArray(96 * 1024) { 3 }
        ZstdDictionary().use { history ->
            history.update(ByteArray(96 * 1024) { 9 })
            val raw = varInt(packet.size) + packet
            val independent = compress(raw, 1, null)
            assertTrue(independent.size < 96, "Fixture must exercise the mathematical skip bound")
            val input = Unpooled.wrappedBuffer(packet)
            val frame = try { ZstdFrame.encode(UnpooledByteBufAllocator.DEFAULT, input, 1, history) }
            finally { input.release() }
            assertNotNull(frame)
            try {
                val length = readVarInt(frame)
                assertEquals(length, frame.readableBytes())
                assertEquals(1, frame.readUnsignedByte().toInt())
                val compressed = ByteArray(frame.readableBytes()).also { frame.readBytes(it) }
                assertContentEquals(independent, compressed)
                assertContentEquals(raw, ZstdFrame.decode(compressed))
                ZstdDictionary().use { expected ->
                    expected.update(raw)
                    assertContentEquals(expected.digest(), history.digest())
                }
            } finally { frame.release() }
        }
    }

    // Deliberately retain the separate-context algorithm here as a regression oracle.
    // Production code does not call it; this isn't a throughput benchmark.
    private fun originalEncoding(packet: ByteArray, level: Int, history: ZstdDictionary): ByteArray? {
        val raw = varInt(packet.size) + packet
        val independent = compress(raw, level, null)
        val trial = if (history.ready()) compress(raw, level, history.content()) else null
        val useDictionary = trial != null && trial.size + ZstdDictionary.HASH_BYTES + 64 <= independent.size
        val compressed = if (useDictionary) checkNotNull(trial) else independent
        val body = byteArrayOf(if (useDictionary) 2 else 1) +
            (if (useDictionary) history.digest() else ByteArray(0)) + compressed
        if (body.size >= raw.size) return null
        history.update(raw)
        return varInt(body.size) + body
    }
    private fun compress(raw: ByteArray, level: Int, dictionary: ByteArray?): ByteArray = ZstdCompressCtx().use { ctx ->
        ctx.setLevel(level).setWindowLog(23).setContentSize(true).setChecksum(true)
        if (dictionary != null) ctx.loadDict(dictionary)
        ctx.compress(raw)
    }
    private fun varInt(value: Int): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        do {
            val b = v and 127; v = v ushr 7
            out.write(if (v == 0) b else b or 128)
        } while (v != 0)
        return out.toByteArray()
    }
    private fun readVarInt(buf: io.netty.buffer.ByteBuf): Int {
        var value = 0
        for (i in 0 until 5) {
            val b = buf.readUnsignedByte().toInt(); value = value or ((b and 127) shl (i * 7))
            if (b and 128 == 0) return value
        }
        error("Invalid VarInt")
    }
}
