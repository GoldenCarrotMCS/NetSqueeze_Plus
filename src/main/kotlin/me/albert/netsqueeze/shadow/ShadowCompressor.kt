package me.albert.netsqueeze.shadow

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import java.util.zip.Deflater

/**
 * 影子压缩:挂在 compress 前面(出站方向先经过它),看到的是 Via/PacketEvents 处理完、原版压缩之前的单个包。
 * 包照原样往下传,只把它的字节喂进本连接独享的 deflate 流;每次 flush 做一次 SYNC_FLUSH,
 * 产出的字节数就是"这一 tick 攒下的包打成一帧、跨包共用上下文压缩"时要发的量。
 *
 * 帧内格式按将来真做时的设想估:每个包 varint 长度 + 包字节,整帧外面再套一层 varint 帧长。
 * 全部方法都在这条连接的 netty 线程上跑,不用加锁。
 */
internal class ShadowCompressor(private val tally: PlayerTally) : ChannelOutboundHandlerAdapter() {

    private companion object {
        /** 和 Paper 默认(Velocity natives,compression-level -1)同一档,对比才公平 */
        const val LEVEL = 6

        const val OUT_BUFFER = 64 * 1024
    }

    /** nowrap:真做时帧里不需要 zlib 头和校验和 */
    private val deflater = Deflater(LEVEL, true)
    private val out = ByteArray(OUT_BUFFER)
    private val lengthPrefix = ByteArray(5)

    private var pendingRaw = 0L
    private var pendingOut = 0L
    private var pendingPackets = 0
    private var pendingNanos = 0L
    private var broken = false

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (!broken && msg is ByteBuf && msg.isReadable) {
            try {
                feed(msg)
            } catch (e: Throwable) {
                fail(e)
            }
        }
        ctx.write(msg, promise)
    }

    override fun flush(ctx: ChannelHandlerContext) {
        if (!broken && pendingPackets > 0) {
            try {
                endFrame()
            } catch (e: Throwable) {
                fail(e)
            }
        }
        ctx.flush()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        // 连接关闭时 netty 拆管线也会走这里,deflate 流占着原生内存,必须 end
        if (broken) return
        broken = true
        deflater.end()
    }

    private fun feed(buf: ByteBuf) {
        val start = System.nanoTime()
        val length = buf.readableBytes()
        val prefix = writeVarInt(length)
        deflater.setInput(lengthPrefix, 0, prefix)
        drainInput()
        // nioBuffer 是视图,不动 buf 的读指针;下面 drainInput 会把它吃完,之后 buf 才往下传、被释放
        deflater.setInput(buf.nioBuffer())
        drainInput()
        pendingRaw += prefix + length
        pendingPackets++
        pendingNanos += System.nanoTime() - start
    }

    private fun endFrame() {
        val start = System.nanoTime()
        do {
            val produced = deflater.deflate(out, 0, out.size, Deflater.SYNC_FLUSH)
            pendingOut += produced
        } while (produced == out.size)
        val frame = pendingOut + varIntSize(pendingOut)
        pendingNanos += System.nanoTime() - start
        ShadowStats.recordShadow(tally, pendingRaw, frame, pendingPackets, pendingNanos)
        pendingRaw = 0
        pendingOut = 0
        pendingPackets = 0
        pendingNanos = 0
    }

    /** NO_FLUSH 下一直压到输入吃完;产出的字节先记着,flush 时连同 SYNC_FLUSH 的尾巴一起算进这一帧 */
    private fun drainInput() {
        while (!deflater.needsInput()) {
            pendingOut += deflater.deflate(out, 0, out.size, Deflater.NO_FLUSH)
        }
    }

    private fun writeVarInt(value: Int): Int {
        var v = value
        var i = 0
        while (v and 0x7F.inv() != 0) {
            lengthPrefix[i++] = (v and 0x7F or 0x80).toByte()
            v = v ushr 7
        }
        lengthPrefix[i++] = v.toByte()
        return i
    }

    private fun fail(e: Throwable) {
        broken = true
        deflater.end()
        ShadowStats.recordFailure(tally.name, e)
    }
}
