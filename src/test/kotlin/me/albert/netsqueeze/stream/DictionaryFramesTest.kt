package me.albert.netsqueeze.stream

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import me.albert.netsqueeze.config.Settings
import me.albert.netsqueeze.protocol.DictionaryProtocol
import me.albert.netsqueeze.protocol.ZstdDictionary
import me.albert.netsqueeze.stats.PlayerTally
import me.albert.netsqueeze.tunnel.TunnelCompressor
import net.minecraft.network.HandlerNames
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.test.*

class DictionaryFramesTest {
    @Test fun `direct and tunnel dictionaries emit real fixtures with deflate interleaving`() {
        assertTrue(ZstdFrame.dictionaryAvailable(), "Dictionary JNI cannot be skipped")
        val block = ByteArray(48 * 1024).also { Random(995).nextBytes(it) }
        val large = block + block
        val changed = large.clone().also { it[90000] = (it[90000].toInt() xor 1).toByte() }
        val entropy = ByteArray(96 * 1024).also { Random(96).nextBytes(it) }
        val small = ByteArray(1024) { 11 }
        val packets = listOf(small, large, small, large, entropy, small, changed, large, small)
        val dir = Path.of(System.getProperty("netsqueeze.fixtures"))
        Files.createDirectories(dir)
        records(dir.resolve("dictionary-expected.bin"), packets)
        val direct = EmbeddedChannel()
        direct.pipeline().addLast(HandlerNames.PREPENDER, ChannelOutboundHandlerAdapter())
        direct.pipeline().addLast("stream", StreamCompressor(useZstd = true, useDictionary = true))
        try {
            val promises = packets.map { direct.write(Unpooled.wrappedBuffer(it)) }
            direct.flushOutbound()
            promises.forEach { assertTrue(it.isSuccess) }
            val frames = mutableListOf<ByteArray>()
            var dictionaryFrames = 0
            while (true) {
                val frame = direct.readOutbound<ByteBuf>() ?: break
                try {
                    val copy = frame.duplicate()
                    val length = readVarInt(copy)
                    assertEquals(length, copy.readableBytes())
                    if (copy.readUnsignedByte().toInt() == ZstdDictionary.MARKER) dictionaryFrames++
                    frames += ByteArray(frame.readableBytes()).also { frame.readBytes(it) }
                } finally { frame.release() }
            }
            assertTrue(dictionaryFrames >= 2, "Correlated large frames should use history")
            Files.write(dir.resolve("dictionary-direct.bin"), frames.fold(ByteArray(0)) { a, b -> a + b })
            records(dir.resolve("dictionary-frames.bin"), frames)
        } finally { direct.finishAndReleaseAll() }
        for (cache in listOf(false, true)) {
            val bridge = TunnelCompressor(Settings(chunkCacheEnabled = cache), PlayerTally("dict")) {}
            val tunnel = EmbeddedChannel(bridge)
            try {
                bridge.acknowledge(useZstd = true, cache = cache, useDictionary = true)
                bridge.candidate = true
                val promises = packets.map { tunnel.write(Unpooled.wrappedBuffer(it)) }
                tunnel.flushOutbound()
                promises.forEach { assertTrue(it.isSuccess) }
                val carriers = mutableListOf<ByteArray>()
                while (true) {
                    val packet = tunnel.readOutbound<ClientboundCustomPayloadPacket>() ?: break
                    val payload = packet.payload() as DiscardedPayload
                    assertEquals(DictionaryProtocol.DATA, payload.id().path)
                    carriers += payload.data()
                }
                records(dir.resolve("dictionary-tunnel-$cache.bin"), carriers)
            } finally { tunnel.finishAndReleaseAll() }
        }
    }

    @Test fun `rejected ZSTD attempts never advance rolling history`() {
        val random = Random(673)
        val packet = Unpooled.wrappedBuffer(ByteArray(96 * 1024).also(random::nextBytes))
        ZstdDictionary().use { dictionary ->
            dictionary.update("previous history".repeat(100).toByteArray())
            val expected = dictionary.digest().clone()
            try {
                val frame = ZstdFrame.encode(packet.alloc(), packet, 1, dictionary)
                if (frame != null) frame.release()
                assertNull(frame, "Unrelated high entropy should safely fall back")
                assertContentEquals(expected, dictionary.digest())
                assertEquals(0, packet.readerIndex())
            } finally { packet.release() }
        }
    }

    @Test fun `dictionary capability disabled or old ack retains old channels`() {
        for (disabled in listOf(false, true)) {
            val bridge = TunnelCompressor(Settings(zstdDictionaryEnabled = !disabled), PlayerTally("fallback")) {}
            val channel = EmbeddedChannel(bridge)
            try {
                if (disabled) {
                    bridge.acknowledge(useZstd = true, useDictionary = true)
                    assertFalse(bridge.enabled)
                }
                bridge.acknowledge(useZstd = true)
                // A later upgraded ACK cannot change the active wire format.
                bridge.acknowledge(useZstd = true, useDictionary = true)
                bridge.candidate = true
                channel.writeOutbound(Unpooled.wrappedBuffer(ByteArray(96000) { 4 }))
                val packet = assertNotNull(channel.readOutbound<ClientboundCustomPayloadPacket>())
                assertEquals(me.albert.netsqueeze.tunnel.TunnelProtocol.DATA, (packet.payload() as DiscardedPayload).id())
            } finally { channel.finishAndReleaseAll() }
        }
    }
    private fun records(path: Path, data: List<ByteArray>) {
        DataOutputStream(Files.newOutputStream(path)).use { out ->
            out.writeInt(data.size); data.forEach { out.writeInt(it.size); out.write(it) }
        }
    }
    private fun readVarInt(buf: ByteBuf): Int {
        var value = 0
        for (i in 0 until 5) {
            val b = buf.readUnsignedByte().toInt(); value = value or ((b and 127) shl (7 * i))
            if (b and 128 == 0) return value
        }
        error("bad VarInt")
    }
}
