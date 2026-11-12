package me.albert.netsqueeze.stream

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import me.albert.netsqueeze.config.Settings
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

class HybridFramesTest {
    @Test
    fun `real transports generate mixed small deflate and large zstd fixtures`() {
        assertTrue(ZstdFrame.available(), "ZSTD natives must actually work in CI")
        val random = Random(219)
        val large = ByteArray(3 * 1024 * 1024).also(random::nextBytes)
        // Repeated 4 KiB pairs remain >256 KiB compressed: exercise multi-fragment ZSTD.
        for (offset in 0 until large.size step 8192) {
            System.arraycopy(large, offset, large, offset + 4096, 4096)
        }
        val small = ByteArray(1024) { (it % 31).toByte() }
        val highEntropy = ByteArray(65536).also(random::nextBytes)
        val packets = listOf(small, ByteArray(65535) { 3 }, ByteArray(65536) { 7 }, small,
            large, small, highEntropy, small)
        val dir = Path.of(System.getProperty("netsqueeze.fixtures"))
        Files.createDirectories(dir)
        DataOutputStream(Files.newOutputStream(dir.resolve("hybrid-expected.bin"))).use { out ->
            out.writeInt(packets.size)
            packets.forEach { out.writeInt(it.size); out.write(it) }
        }
        val direct = EmbeddedChannel()
        direct.pipeline().addLast(HandlerNames.PREPENDER, ChannelOutboundHandlerAdapter())
        direct.pipeline().addLast("stream", StreamCompressor(useZstd = true))
        try {
            val promises = packets.map { direct.write(Unpooled.wrappedBuffer(it)) }
            direct.flushOutbound()
            promises.forEach { assertTrue(it.isSuccess) }
            var deflates = 0
            var zstd = 0
            Files.newOutputStream(dir.resolve("hybrid-wire.bin")).use { out ->
                while (true) {
                    val frame = direct.readOutbound<ByteBuf>() ?: break
                    try {
                        val view = frame.duplicate()
                        assertEquals(view.readableBytes() - varIntSize(view), readVarInt(view))
                        when (view.readUnsignedByte().toInt()) {
                            0 -> deflates++
                            1 -> zstd++
                            else -> fail("Unknown algorithm marker")
                        }
                        val bytes = ByteArray(frame.readableBytes())
                        frame.readBytes(bytes)
                        out.write(bytes)
                    } finally { frame.release() }
                }
            }
            assertTrue(deflates >= 3)
            assertEquals(2, zstd, "Only two large compressible packets should use ZSTD")
        } finally { direct.finishAndReleaseAll() }
        val bridge = TunnelCompressor(Settings(), PlayerTally("hybrid")) {}
        val tunnel = EmbeddedChannel(bridge)
        try {
            bridge.acknowledge(useZstd = true)
            bridge.candidate = true
            val promises = packets.map { tunnel.write(Unpooled.wrappedBuffer(it)) }
            tunnel.flushOutbound()
            promises.forEach { assertTrue(it.isSuccess) }
            val carriers = ArrayList<ByteArray>()
            while (true) {
                val packet = tunnel.readOutbound<ClientboundCustomPayloadPacket>() ?: break
                carriers += (packet.payload() as DiscardedPayload).data()
            }
            assertTrue(carriers.size > packets.size, "Large ZSTD must use more than one fragment")
            DataOutputStream(Files.newOutputStream(dir.resolve("hybrid-tunnel.bin"))).use { out ->
                out.writeInt(carriers.size)
                carriers.forEach { out.writeInt(it.size); out.write(it) }
            }
        } finally { tunnel.finishAndReleaseAll() }
    }

    @Test
    fun `legacy ACK remains unmarked even if duplicate modern ACK arrives`() {
        val bridge = TunnelCompressor(Settings(), PlayerTally("legacy")) {}
        val ch = EmbeddedChannel(bridge)
        try {
            bridge.acknowledge(useZstd = false, marked = false)
            bridge.acknowledge(useZstd = true, marked = true)
            bridge.candidate = true
            ch.writeOutbound(Unpooled.wrappedBuffer(ByteArray(128 * 1024) { 3 }))
            val packet = assertNotNull(ch.readOutbound<ClientboundCustomPayloadPacket>())
            assertEquals(me.albert.netsqueeze.tunnel.TunnelProtocol.LEGACY_DATA,
                (packet.payload() as DiscardedPayload).id())
            assertNull(ch.readOutbound<Any>())
        } finally { ch.finishAndReleaseAll() }
    }

    @Test
    fun `disabled ZSTD sends deflate even when client advertises ZSTD`() {
        val bridge = TunnelCompressor(Settings(zstdEnabled = false), PlayerTally("disabled")) {}
        val ch = EmbeddedChannel(bridge)
        try {
            bridge.acknowledge(useZstd = true)
            bridge.candidate = true
            ch.writeOutbound(Unpooled.wrappedBuffer(ByteArray(512 * 1024) { 9 }))
            val packet = assertNotNull(ch.readOutbound<ClientboundCustomPayloadPacket>())
            val buf = Unpooled.wrappedBuffer((packet.payload() as DiscardedPayload).data())
            try {
                buf.skipBytes(me.albert.netsqueeze.protocol.TunnelProtocol.HEADER_BYTES)
                val length = readVarInt(buf)
                assertEquals(length, buf.readableBytes())
                assertEquals(0, buf.readUnsignedByte().toInt())
            } finally { buf.release() }
        } finally { ch.finishAndReleaseAll() }
    }

    private fun varIntSize(buf: ByteBuf): Int {
        for (i in 0 until 5) if (buf.getUnsignedByte(buf.readerIndex() + i).toInt() and 128 == 0) return i + 1
        fail("Invalid VarInt")
    }
    private fun readVarInt(buf: ByteBuf): Int {
        var result = 0
        for (i in 0 until 5) {
            val byte = buf.readUnsignedByte().toInt()
            result = result or ((byte and 127) shl (7 * i))
            if (byte and 128 == 0) return result
        }
        fail("Invalid VarInt")
    }
}
