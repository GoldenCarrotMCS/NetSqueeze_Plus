package me.albert.netsqueeze.tunnel

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import me.albert.netsqueeze.config.Settings
import me.albert.netsqueeze.protocol.ChunkCacheProtocol
import me.albert.netsqueeze.stats.PlayerTally
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.test.*

class CacheTunnelFixtureTest {
    @Test
    fun `real v4 server compression produces full and reference fixtures`() {
        val bridge = TunnelCompressor(Settings(chunkCacheEnabled = true), PlayerTally("fixture")) {}
        val ch = EmbeddedChannel(bridge)
        try {
            bridge.acknowledge(cache = true)
            bridge.candidate = true
            // Exercise the real v4 framing without scheduling a worker on EmbeddedChannel.
            val full = byteArrayOf(0) + ByteArray(4096).also { Random(77).nextBytes(it); it[0] = 5 }
            val a = ch.write(Unpooled.wrappedBuffer(full.copyOfRange(1, full.size)))
            ch.flushOutbound()
            assertTrue(a.isSuccess)
            val carriers = mutableListOf<ByteArray>()
            while (true) {
                val packet = ch.readOutbound<ClientboundCustomPayloadPacket>() ?: break
                val payload = packet.payload() as DiscardedPayload
                assertEquals(ChunkCacheProtocol.DATA, payload.id().path)
                carriers += payload.data()
            }
            assertTrue(carriers.isNotEmpty())
            val dir = Path.of(System.getProperty("netsqueeze.fixtures"))
            Files.createDirectories(dir)
            DataOutputStream(Files.newOutputStream(dir.resolve("cache-v4.bin"))).use { out ->
                out.writeInt(carriers.size)
                carriers.forEach { out.writeInt(it.size); out.write(it) }
            }
            Files.write(dir.resolve("cache-v4-expected.bin"), full)
        } finally { ch.finishAndReleaseAll() }
    }
}
