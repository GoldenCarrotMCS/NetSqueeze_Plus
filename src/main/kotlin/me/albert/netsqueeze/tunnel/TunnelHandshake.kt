package me.albert.netsqueeze.tunnel

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import me.albert.netsqueeze.platform.ServerNetwork

internal class TunnelHandshake(private val bridge: TunnelCompressor) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (ServerNetwork.packetAccess().payloadId(msg) == TunnelProtocol.ack(bridge.session, bridge.protocol)) {
            bridge.acknowledge()
            return
        }
        if (msg.javaClass.name.startsWith("net.minecraft.network.protocol.game.Serverbound")) bridge.inboundPlayPacket()
        ctx.fireChannelRead(msg)
    }
}
