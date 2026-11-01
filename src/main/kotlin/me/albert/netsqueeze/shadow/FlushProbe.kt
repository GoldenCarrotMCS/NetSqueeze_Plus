package me.albert.netsqueeze.shadow

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import me.albert.netsqueeze.stats.FlushStats

/**
 * flush 来源探针(诊断用,查清后删):挂在管线最尾(出站最先经过它),这里拿到的还是包对象。
 * 记下每次 flush 前写了几个包,小 flush 顺带记下是哪几种包,交给 [FlushStats]。
 * 只看得到从 channel 尾部写进来的包,中间处理器(Via、Grim 之类)自己用 ctx.write 补发的看不到。
 */
internal class FlushProbe : ChannelOutboundHandlerAdapter() {

    private var count = 0
    private val small = arrayOfNulls<Any>(FlushStats.SMALL)

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val type = if (msg is ByteBuf) peekId(msg) else msg.javaClass
        FlushStats.recordPacket(type)
        if (count < small.size) small[count] = type
        count++
        ctx.write(msg, promise)
    }

    override fun flush(ctx: ChannelHandlerContext) {
        FlushStats.recordFlush(count, small)
        count = 0
        ctx.flush()
    }

    /** 读开头的 varint 包 id,不动读指针;读不出来记 -1 */
    private fun peekId(buf: ByteBuf): Int {
        var id = 0
        for (i in 0..<5) {
            val index = buf.readerIndex() + i
            if (index >= buf.writerIndex()) return -1
            val b = buf.getByte(index).toInt()
            id = id or ((b and 0x7F) shl (7 * i))
            if (b and 0x80 == 0) return id
        }
        return -1
    }
}
