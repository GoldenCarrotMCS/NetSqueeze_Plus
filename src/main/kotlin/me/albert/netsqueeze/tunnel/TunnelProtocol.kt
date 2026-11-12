package me.albert.netsqueeze.tunnel

import me.albert.netsqueeze.protocol.TunnelProtocol as Wire
import me.albert.netsqueeze.platform.ServerNetwork
import java.util.UUID

internal object TunnelProtocol {
    val OFFER: Any by lazy { ServerNetwork.packetAccess().id(Wire.OFFER_PATH) }
    val STOP: Any by lazy { ServerNetwork.packetAccess().id(Wire.STOP_PATH) }
    val DATA: Any by lazy { ServerNetwork.packetAccess().id(Wire.DATA_PATH) }
    val LEGACY_OFFER: Any by lazy { ServerNetwork.packetAccess().id(Wire.LEGACY_OFFER_PATH) }
    val LEGACY_STOP: Any by lazy { ServerNetwork.packetAccess().id(Wire.LEGACY_STOP_PATH) }
    val LEGACY_DATA: Any by lazy { ServerNetwork.packetAccess().id(Wire.LEGACY_DATA_PATH) }
    fun ack(session: UUID, protocol: Int = ServerNetwork.protocolVersion(), marked: Boolean = false, zstd: Boolean = false): Any =
        ServerNetwork.packetAccess().id(Wire.ackPath(session, protocol, marked, zstd))
    fun offer(session: UUID, protocol: Int, marked: Boolean, zstd: Boolean): Any =
        packet(ServerNetwork.packetAccess().id(Wire.offerPath(marked)),
            if (marked) Wire.encodeOffer(session, protocol, zstd) else Wire.encodeOffer(session, protocol))
    fun data(marked: Boolean): Any = ServerNetwork.packetAccess().id(Wire.dataPath(marked))
    fun stop(marked: Boolean): Any = ServerNetwork.packetAccess().id(Wire.stopPath(marked))
    fun packet(id: Any, bytes: ByteArray): Any = ServerNetwork.packetAccess().packet(id, bytes)
}
