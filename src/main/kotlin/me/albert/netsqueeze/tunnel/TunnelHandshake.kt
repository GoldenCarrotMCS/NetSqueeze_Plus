package me.albert.netsqueeze.tunnel

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket

internal class TunnelHandshake(private val bridge: TunnelCompressor) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is ServerboundCustomPayloadPacket && msg.payload().type().id() == TunnelProtocol.ack(bridge.session)) {
            bridge.acknowledge()
            return
        }
        if (msg.javaClass.name.startsWith("net.minecraft.network.protocol.game.Serverbound")) bridge.inboundPlayPacket()
        ctx.fireChannelRead(msg)
    }
}
