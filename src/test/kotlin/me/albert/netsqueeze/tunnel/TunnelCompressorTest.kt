package me.albert.netsqueeze.tunnel

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import me.albert.netsqueeze.config.Settings
import me.albert.netsqueeze.protocol.TunnelProtocol as Wire
import me.albert.netsqueeze.stats.PlayerTally
import net.minecraft.network.HandlerNames
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.test.*

class TunnelCompressorTest {
    @Test
    fun `real tunnel compressor generates bounded proxy carrier fixtures`() {
        val dir = Path.of(System.getProperty("netsqueeze.fixtures"))
        Files.createDirectories(dir)
        val random = Random(171)
        val packets = listOf(ByteArray(3 * 1024 * 1024).also(random::nextBytes)) +
            List(20) { ByteArray(512) { (it % 17).toByte() } }
        DataOutputStream(Files.newOutputStream(dir.resolve("tunnel-expected.bin"))).use { out ->
            out.writeInt(packets.size)
            packets.forEach { out.writeInt(it.size); out.write(it) }
        }
        for (level in listOf(0, 6, 9)) {
            val bridge = TunnelCompressor(Settings(compressionLevel = level), PlayerTally("test")) {}
            val ch = EmbeddedChannel(bridge)
            try {
                bridge.acknowledge()
                bridge.candidate = true // 这里输入的是已经编码的原包；gate 另测。
                val promises = packets.mapIndexed { i, packet ->
                    val promise = ch.write(Unpooled.wrappedBuffer(packet))
                    if (i % 4 == 0) ch.flushOutbound()
                    promise
                }
                ch.flushOutbound()
                promises.forEach { assertTrue(it.isSuccess) }
                val payloads = ArrayList<ByteArray>()
                while (true) {
                    val packet = ch.readOutbound<ClientboundCustomPayloadPacket>() ?: break
                    val payload = packet.payload() as DiscardedPayload
                    assertEquals(TunnelProtocol.DATA, payload.id())
                    assertTrue(payload.data().size <= Wire.HEADER_BYTES + Wire.FRAGMENT_BYTES)
                    payloads += payload.data()
                }
                assertTrue(payloads.size > 1)
                DataOutputStream(Files.newOutputStream(dir.resolve("tunnel-$level.bin"))).use { out ->
                    out.writeInt(payloads.size)
                    payloads.forEach { out.writeInt(it.size); out.write(it) }
                }
            } finally { ch.finishAndReleaseAll() }
        }
    }

    @Test
    fun `gate preserves control ordering and never recursively wraps payloads`() {
        val bridge = TunnelCompressor(Settings(), PlayerTally("test")) {}
        val ch = EmbeddedChannel(bridge)
        ch.pipeline().addLast(HandlerNames.ENCODER, object : ChannelOutboundHandlerAdapter() {
            override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                if (msg is ClientboundSetEntityMotionPacket) ctx.write(Unpooled.wrappedBuffer(ByteArray(512) { 1 }), promise)
                else ctx.write(msg, promise)
            }
        })
        ch.pipeline().addLast(TunnelGate(bridge))
        try {
            bridge.acknowledge()
            val original = ch.write(ClientboundSetEntityMotionPacket(1, Vec3.ZERO))
            assertFalse(original.isDone)
            val control = Any()
            ch.write(control)
            ch.flushOutbound()
            assertTrue(original.isSuccess)
            assertIs<ClientboundCustomPayloadPacket>(ch.readOutbound<Any>())
            assertSame(control, ch.readOutbound<Any>())
            assertNull(ch.readOutbound<Any>())
        } finally { ch.finishAndReleaseAll() }
    }

    @Test
    fun `no acknowledgment means vanilla and pause resets session`() {
        val bridge = TunnelCompressor(Settings(), PlayerTally("test")) {}
        val ch = EmbeddedChannel(bridge)
        try {
            bridge.candidate = true
            val input = Unpooled.wrappedBuffer(byteArrayOf(9))
            ch.writeOutbound(input)
            assertSame(input, ch.readOutbound<Any>())
            input.release()
            bridge.acknowledge()
            val old = bridge.session
            bridge.pause()
            ch.flushOutbound()
            assertFalse(bridge.enabled)
            assertNotEquals(old, bridge.session)
            val stop = assertNotNull(ch.readOutbound<ClientboundCustomPayloadPacket>())
            assertEquals(TunnelProtocol.STOP, (stop.payload() as DiscardedPayload).id())
        } finally { ch.finishAndReleaseAll() }
    }

    @Test
    fun `small packets keep vanilla bytes without advancing the compression stream`() {
        val bridge = TunnelCompressor(Settings(), PlayerTally("test")) {}
        val ch = EmbeddedChannel(bridge)
        try {
            bridge.acknowledge()
            bridge.candidate = true
            val small = Unpooled.wrappedBuffer(byteArrayOf(1, 2))
            ch.writeOutbound(small)
            assertSame(small, ch.readOutbound<Any>())
            small.release()
            ch.writeOutbound(Unpooled.wrappedBuffer(ByteArray(1024) { 7 }))
            assertIs<ClientboundCustomPayloadPacket>(ch.readOutbound<Any>())
            assertNull(ch.readOutbound<Any>())
        } finally { ch.finishAndReleaseAll() }
    }

    @Test
    fun `old or invented ACK does not enable the tunnel`() {
        val bridge = TunnelCompressor(Settings(), PlayerTally("test")) {}
        val ch = EmbeddedChannel(bridge, TunnelHandshake(bridge))
        try {
            val wrong = net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                DiscardedPayload(TunnelProtocol.ack(java.util.UUID.randomUUID()), ByteArray(0)))
            ch.writeInbound(wrong)
            assertFalse(bridge.enabled)
            assertSame(wrong, ch.readInbound<Any>())
            ch.writeInbound(net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                DiscardedPayload(TunnelProtocol.ack(bridge.session), ByteArray(0))))
            assertTrue(bridge.enabled)
            assertNull(ch.readInbound<Any>())
        } finally { ch.finishAndReleaseAll() }
    }

    @Test
    fun `whitelist excludes proxy state and control packets`() {
        for (name in listOf("ClientboundKeepAlivePacket", "ClientboundLoginPacket", "ClientboundRespawnPacket",
            "ClientboundStartConfigurationPacket", "ClientboundPlayerChatPacket", "ClientboundTransferPacket")) {
            assertFalse(Wire.allowed("net.minecraft.network.protocol.game.$name"))
        }
        assertTrue(Wire.allowed("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket"))
        assertFalse(Wire.allowed("other.ClientboundLevelChunkWithLightPacket"))
    }
}
