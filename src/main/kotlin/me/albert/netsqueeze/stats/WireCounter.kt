package me.albert.netsqueeze.stats

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise

/**
 * 原版出网计数(只挂没装 mod 的玩家):挂在 prepender 后面(加密前,AES/CFB8 不改长度),数的是真正写进 socket 的字节。
 * 每次 flush 结一笔,顺带按 MSS 估这次 flush 至少占几个 TCP 段。
 */
internal class WireCounter(private val tally: PlayerTally) : ChannelOutboundHandlerAdapter() {

    private var pending = 0L

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is ByteBuf) pending += msg.readableBytes()
        ctx.write(msg, promise)
    }

    override fun flush(ctx: ChannelHandlerContext) {
        if (pending > 0) {
            TrafficStats.recordWire(tally, pending)
            pending = 0
        }
        ctx.flush()
    }
}
