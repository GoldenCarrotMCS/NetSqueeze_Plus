package me.albert.netsqueeze.tunnel

import me.albert.netsqueeze.protocol.TunnelProtocol as Wire
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer
import java.util.UUID

internal object TunnelProtocol {
    val OFFER: Identifier = Identifier.fromNamespaceAndPath("netsqueeze", Wire.OFFER_PATH)
    val STOP: Identifier = Identifier.fromNamespaceAndPath("netsqueeze", Wire.STOP_PATH)
    val DATA: Identifier = Identifier.fromNamespaceAndPath("netsqueeze", Wire.DATA_PATH)

    fun ack(session: UUID): Identifier = Identifier.fromNamespaceAndPath("netsqueeze", Wire.ackPath(session))
    fun offer(session: UUID) = packet(OFFER, ByteBuffer.allocate(16)
        .putLong(session.mostSignificantBits).putLong(session.leastSignificantBits).array())
    fun packet(id: Identifier, bytes: ByteArray) = ClientboundCustomPayloadPacket(DiscardedPayload(id, bytes))
}
