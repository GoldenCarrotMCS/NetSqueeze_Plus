package me.albert.netsqueeze.tunnel

import me.albert.netsqueeze.protocol.TunnelProtocol as Wire
import me.albert.netsqueeze.platform.ServerNetwork
import java.util.UUID

internal object TunnelProtocol {
    val OFFER: Any by lazy { ServerNetwork.packetAccess().id(Wire.OFFER_PATH) }
    val STOP: Any by lazy { ServerNetwork.packetAccess().id(Wire.STOP_PATH) }
    val DATA: Any by lazy { ServerNetwork.packetAccess().id(Wire.DATA_PATH) }
    fun ack(session: UUID, protocol: Int = ServerNetwork.protocolVersion()): Any =
        ServerNetwork.packetAccess().id(Wire.ackPath(session, protocol))
    fun offer(session: UUID, protocol: Int = ServerNetwork.protocolVersion()): Any =
        packet(OFFER, Wire.encodeOffer(session, protocol))
    fun packet(id: Any, bytes: ByteArray): Any = ServerNetwork.packetAccess().packet(id, bytes)
}
