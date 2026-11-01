package me.albert.netsqueeze.shadow

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import me.albert.netsqueeze.stats.PlayerTally
import me.albert.netsqueeze.stats.ShadowStats
import me.albert.netsqueeze.stream.FrameDeflater

/**
 * 影子压缩:挂在 compress 前面(出站方向先经过它),看到的是 Via/PacketEvents 处理完、原版压缩之前的单个包。
 * 包照原样往下传,只把它的字节喂进本连接独享的 [FrameDeflater];每次 flush 收成一帧,量完大小就丢,
 * 得出"这一 tick 攒下的包打成一帧、跨包共用上下文压缩"时要发的量。帧格式和装了 mod 的真压缩完全一样。
 * 全部方法都在这条连接的 netty 线程上跑,不用加锁。
 */
internal class ShadowCompressor(private val tally: PlayerTally) : ChannelOutboundHandlerAdapter() {

    private lateinit var deflater: FrameDeflater
    private var pendingNanos = 0L
    private var broken = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        deflater = FrameDeflater(ctx.alloc())
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (!broken && msg is ByteBuf && msg.isReadable) {
            val start = System.nanoTime()
            try {
                deflater.feed(msg)
            } catch (e: Throwable) {
                fail(e)
            }
            pendingNanos += System.nanoTime() - start
        }
        ctx.write(msg, promise)
    }

    override fun flush(ctx: ChannelHandlerContext) {
        if (!broken && deflater.pendingPackets > 0) {
            try {
                endFrame()
            } catch (e: Throwable) {
                fail(e)
            }
        }
        ctx.flush()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        // 连接关闭时 netty 拆管线也会走这里,deflate 流占着原生内存,必须关
        if (broken) return
        broken = true
        deflater.close()
    }

    private fun endFrame() {
        val start = System.nanoTime()
        val raw = deflater.pendingRaw
        val packets = deflater.pendingPackets
        val frame = deflater.finish() ?: return
        val size = frame.readableBytes().toLong()
        frame.release()
        pendingNanos += System.nanoTime() - start
        ShadowStats.recordShadow(tally, raw, size, packets, pendingNanos)
        pendingNanos = 0
    }

    private fun fail(e: Throwable) {
        broken = true
        deflater.close()
        ShadowStats.recordFailure(tally.name, e)
    }
}
