package me.albert.netsqueeze.tunnel

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import me.albert.netsqueeze.platform.ServerNetwork

internal class TunnelHandshake(private val bridge: TunnelCompressor) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val packetId = ServerNetwork.packetAccess().payloadId(msg)
        if (packetId == bridge.cacheAck(true)) { bridge.acknowledge(useZstd = true, cache = true); return }
        if (packetId == bridge.cacheAck(false)) { bridge.acknowledge(useZstd = false, cache = true); return }
        if (bridge.cacheSeed(packetId)) return
        if (packetId == bridge.ack(true)) {
            bridge.acknowledge(useZstd = true)
            return
        }
        if (packetId == bridge.ack(false)) {
            bridge.acknowledge(useZstd = false)
            return
        }
        if (packetId == bridge.ack(zstd = false, marked = false)) {
            bridge.acknowledge(useZstd = false, marked = false)
            return
        }
        if (msg.javaClass.name.startsWith("net.minecraft.network.protocol.game.Serverbound")) bridge.inboundPlayPacket()
        ctx.fireChannelRead(msg)
    }
}
