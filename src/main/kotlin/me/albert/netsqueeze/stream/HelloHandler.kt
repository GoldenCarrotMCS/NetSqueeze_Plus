package me.albert.netsqueeze.stream

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import me.albert.netsqueeze.netty.Injector
import me.albert.netsqueeze.platform.ServerNetwork

internal class HelloHandler : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (ServerNetwork.packetAccess().payloadId(msg) != Protocol.HELLO) {
            ctx.fireChannelRead(msg)
            return
        }
        ctx.pipeline().remove(this)
        Injector.enableStream(ctx.channel())
    }
}
