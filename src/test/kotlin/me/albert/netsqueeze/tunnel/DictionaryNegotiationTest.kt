package me.albert.netsqueeze.tunnel

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import me.albert.netsqueeze.config.Settings
import me.albert.netsqueeze.protocol.DictionaryProtocol
import me.albert.netsqueeze.protocol.ZstdDictionary
import me.albert.netsqueeze.stats.PlayerTally
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import net.minecraft.resources.Identifier
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.*

class DictionaryNegotiationTest {
    @Test fun `real ACK selects v5 and pause resets history and session`() {
        val bridge = TunnelCompressor(Settings(), PlayerTally("dictionary")) {}
        val ch = EmbeddedChannel(bridge, TunnelHandshake(bridge))
        val pattern = ByteArray(48 * 1024).also { Random(1119).nextBytes(it) }
        val large = pattern + pattern
        try {
            val old = bridge.session
            val ack = bridge.dictionaryAck(false) as Identifier
            ch.writeInbound(ServerboundCustomPayloadPacket(DiscardedPayload(ack, byteArrayOf())))
            assertTrue(bridge.enabled)
            bridge.candidate = true
            ch.writeOutbound(Unpooled.wrappedBuffer(large))
            assertEquals(1, marker(ch))
            ch.writeOutbound(Unpooled.wrappedBuffer(large))
            assertEquals(ZstdDictionary.MARKER, marker(ch))
            bridge.pause(); ch.flushOutbound()
            val stop = ch.readOutbound<ClientboundCustomPayloadPacket>().payload() as DiscardedPayload
            assertEquals(DictionaryProtocol.STOP, stop.id().path)
            assertNotEquals(old, bridge.session)
            assertFalse(bridge.enabled)
            ch.writeInbound(ServerboundCustomPayloadPacket(DiscardedPayload(ack, byteArrayOf())))
            assertFalse(bridge.enabled, "Stale session ACK must not enable a new session")
            ch.readInbound<Any>()
            bridge.resumeAfterGamePacket()
            while (ch.readOutbound<Any>() != null) { }
            ch.writeInbound(ServerboundCustomPayloadPacket(DiscardedPayload(bridge.dictionaryAck(false) as Identifier, byteArrayOf())))
            assertTrue(bridge.enabled)
            bridge.candidate = true
            ch.writeOutbound(Unpooled.wrappedBuffer(large))
            assertEquals(1, marker(ch), "New session must bootstrap without old history")
        } finally { ch.finishAndReleaseAll() }
    }
    @Test fun `new offer precedes legacy offers and includes cache permission flag`() {
        val bridge = TunnelCompressor(Settings(chunkCacheEnabled = true), PlayerTally("offer")) {}
        val ch = EmbeddedChannel(bridge)
        try {
            bridge.offer()
            val first = ch.readOutbound<ClientboundCustomPayloadPacket>().payload() as DiscardedPayload
            assertEquals(DictionaryProtocol.OFFER, first.id().path)
            val offer = DictionaryProtocol.decode(first.data())
            assertTrue(offer.cache()); assertTrue(offer.base().zstd())
            assertEquals(bridge.session, offer.base().session())
            val next = ch.readOutbound<ClientboundCustomPayloadPacket>().payload() as DiscardedPayload
            assertEquals(me.albert.netsqueeze.protocol.ChunkCacheProtocol.OFFER, next.id().path)
        } finally { ch.finishAndReleaseAll() }
    }
    @Test fun `dictionary ACK identifiers are reused only within their session`() {
        val bridge = TunnelCompressor(Settings(chunkCacheEnabled = true), PlayerTally("ack-cache")) {}
        val ch = EmbeddedChannel(bridge, TunnelHandshake(bridge))
        try {
            val plain = bridge.dictionaryAck(false)
            val cache = bridge.dictionaryAck(true)
            repeat(100) {
                assertSame(plain, bridge.dictionaryAck(false))
                assertSame(cache, bridge.dictionaryAck(true))
            }
            assertNotEquals(plain, cache)
            val firstSession = bridge.session
            bridge.pause()
            assertNotEquals(firstSession, bridge.session)
            assertNotEquals(plain, bridge.dictionaryAck(false))
            assertNotEquals(cache, bridge.dictionaryAck(true))
            assertSame(bridge.dictionaryAck(false), bridge.dictionaryAck(false))
            assertSame(bridge.dictionaryAck(true), bridge.dictionaryAck(true))
        } finally { ch.finishAndReleaseAll() }
    }
    @Test fun `non payload fast path forwards packets and preserves invitation retries`() {
        val bridge = TunnelCompressor(Settings(zstdDictionaryEnabled = false), PlayerTally("ordinary")) {}
        val ch = EmbeddedChannel(bridge, TunnelHandshake(bridge))
        try {
            repeat(101) { index ->
                val packet = net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(index)
                ch.writeInbound(packet)
                assertSame(packet, ch.readInbound<Any>())
                val retry = index + 1 in listOf(1, 20, 100)
                if (retry) {
                    assertEquals(TunnelProtocol.OFFER, (ch.readOutbound<ClientboundCustomPayloadPacket>().payload() as DiscardedPayload).id())
                    assertEquals(TunnelProtocol.LEGACY_OFFER, (ch.readOutbound<ClientboundCustomPayloadPacket>().payload() as DiscardedPayload).id())
                }
                assertNull(ch.readOutbound<Any>())
            }
            assertFalse(bridge.enabled)
        } finally { ch.finishAndReleaseAll() }
    }
    private fun marker(ch: EmbeddedChannel): Int {
        val packet = ch.readOutbound<ClientboundCustomPayloadPacket>()
        val payload = packet.payload() as DiscardedPayload
        assertEquals(DictionaryProtocol.DATA, payload.id().path)
        val bytes = Unpooled.wrappedBuffer(payload.data())
        try {
            bytes.skipBytes(me.albert.netsqueeze.protocol.TunnelProtocol.HEADER_BYTES)
            for (i in 0 until 5) { val b = bytes.readUnsignedByte().toInt(); if (b and 128 == 0) return bytes.readUnsignedByte().toInt() }
            error("bad length")
        } finally { bytes.release() }
    }
}
