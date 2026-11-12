package me.albert.netsqueeze.stream

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import me.albert.netsqueeze.netty.Injector
import me.albert.netsqueeze.platform.ServerNetwork

internal class HelloHandler : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val id = ServerNetwork.packetAccess().payloadId(msg)
        val legacy = id == Protocol.LEGACY_HELLO
        if (!legacy && id != Protocol.HELLO && id != Protocol.HELLO_ZSTD) {
            ctx.fireChannelRead(msg)
            return
        }
        ctx.pipeline().remove(this)
        Injector.enableStream(ctx.channel(), id == Protocol.HELLO_ZSTD, !legacy)
    }
}
