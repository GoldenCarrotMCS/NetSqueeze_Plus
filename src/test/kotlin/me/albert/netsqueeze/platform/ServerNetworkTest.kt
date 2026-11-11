package me.albert.netsqueeze.platform

import me.albert.netsqueeze.protocol.MinecraftVersions
import me.albert.netsqueeze.protocol.TunnelProtocol
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class ServerNetworkTest {
    @Test
    fun `release eligibility accepts 26 and later but rejects snapshots`() {
        for (version in listOf("26.1", "26.1.1", "26.1.2", "26.2", "26.3", "26.4", "27.1")) {
            assertTrue(ServerNetwork.supports(version), version)
        }
        for (version in listOf("1.21.11", "25.4", "26.2-pre1", "26.2-snapshot", "26", "")) {
            assertFalse(ServerNetwork.supports(version), version)
        }
    }

    @Test
    fun `cached adapter contracts resolve on the current server bundle`() {
        assertTrue(ServerNetwork.verifyApi().isNotEmpty())
        val access = ServerNetwork.packetAccess()
        val packet = access.packet(access.id("adapter_probe"), byteArrayOf(1, 2, 3))
        assertEquals("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", packet.javaClass.name)
        assertNotNull(access.reconnectPacket())
        assertTrue(access.protocolVersion() > 0)
    }

    @Test
    fun `v2 offer validates actual protocol and rejects mismatches`() {
        val session = UUID.randomUUID()
        val protocol = ServerNetwork.protocolVersion()
        val offer = TunnelProtocol.decodeOffer(TunnelProtocol.encodeOffer(session, protocol))
        assertEquals(session, offer.session())
        assertEquals(protocol, offer.protocol())
        assertTrue(MinecraftVersions.protocolMatches(protocol, protocol))
        assertFalse(MinecraftVersions.protocolMatches(protocol, protocol + 1))
        assertFalse(MinecraftVersions.protocolMatches(0x40000001, 0x40000001))
        assertFailsWith<IllegalArgumentException> { TunnelProtocol.decodeOffer(ByteArray(16)) }
        assertNotEquals(TunnelProtocol.ackPath(session, protocol), TunnelProtocol.ackPath(session, protocol + 1))
    }
}
