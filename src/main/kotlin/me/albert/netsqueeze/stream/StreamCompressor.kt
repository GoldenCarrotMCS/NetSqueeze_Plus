package me.albert.netsqueeze.stream

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.PromiseNotifier
import me.albert.netsqueeze.stats.PlayerTally
import me.albert.netsqueeze.stats.StreamStats
import me.albert.netsqueeze.platform.NetworkNames as HandlerNames
import me.albert.netsqueeze.platform.VarInts as VarInt
import java.nio.channels.ClosedChannelException

/**
 * 真流式压缩(装了 mod 的连接):挂在 compress 尾侧(出站先经过它),看到的是 Via/PacketEvents 处理完的单个包。
 * 包不再往 compress 传,喂进本连接的 [FrameDeflater];每次 flush 收成一帧,从 prepender 的 context 往下写,
 * 绕过原版的逐包压缩和长度前缀。compress、prepender 留在管线里不拆:Via、PacketEvents 按名字找它们。
 *
 * 原包的 promise 攒着,等所在那一帧写完一起完成(断线包带"发完再关"的监听,不能提前完成)。
 * 不是 ByteBuf 的出站消息(原版切协议的配置任务等)原样往下传。
 * 全部方法都在这条连接的 netty 线程上跑。出错退不回原版格式(客户端已经切了),只能断开。
 */
internal class StreamCompressor(
    private val compressionLevel: Int = 6,
    private val maxFrameRawBytes: Int = 8 * 1024 * 1024,
    private val useZstd: Boolean = false,
    private val zstdMinPacketBytes: Int = 64 * 1024,
    private val zstdLevel: Int = 1,
    private val markedFrames: Boolean = true
) : ChannelOutboundHandlerAdapter() {

    /** 配置阶段切换时还不知道是谁,进服后由 Injector 绑上 */
    @Volatile
    var tally: PlayerTally? = null

    private lateinit var deflater: FrameDeflater
    private val promises = ArrayList<ChannelPromise>()
    private var pendingNanos = 0L
    private var closed = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        deflater = FrameDeflater(ctx.alloc(), compressionLevel, markedFrames)
        StreamStats.active.incrementAndGet()
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (closed) {
            ReferenceCountUtil.release(msg)
            promise.tryFailure(ClosedChannelException())
            return
        }
        if (msg !is ByteBuf) {
            // 不是包字节的原样放过,和 compress/prepender 一样。典型是原版切入站协议时从出站方向写的配置任务
            // (UnconfiguredPipelineHandler 的 lambda),要一路走到头侧 decoder 位置的 inbound_config 才被吃掉。
            // 先把攒着的包收成一帧写出去,顺序才和写入顺序一致
            passThrough(ctx, msg, promise)
            return
        }
        if (!msg.isReadable) {
            msg.release()
            promise.trySuccess()
            return
        }

        try {
            require(msg.readableBytes() <= ZstdFrame.MAX_INPUT) { "NetSqueeze packet exceeds safety limit" }
            if (markedFrames && useZstd && msg.readableBytes() >= zstdMinPacketBytes) {
                if (deflater.pendingPackets > 0) emit(ctx)
                val started = System.nanoTime()
                if (emitZstd(ctx, msg, promise)) {
                    return
                }
                pendingNanos += System.nanoTime() - started
                // 不可压缩的小包/高熵大包回退 Deflate；此时没有推进 ZSTD 或 Deflate 字典。
            }
            // 不新增 flush 或计时器；只提前写出完整子帧，限制缓存和 promise 数量。
            val size = msg.readableBytes().toLong() + VarInt.getByteSize(msg.readableBytes())
            if (deflater.pendingPackets > 0 &&
                (deflater.pendingRaw + size > maxFrameRawBytes || deflater.pendingPackets >= 4096)) emit(ctx)
            val start = System.nanoTime()
            deflater.feed(msg)
            pendingNanos += System.nanoTime() - start
            if (!promise.isVoid) promises += promise
            if (deflater.pendingRaw >= maxFrameRawBytes) emit(ctx)
        } catch (e: Throwable) {
            if (!promise.isVoid) promise.tryFailure(e)
            fail(ctx, e)
            return
        } finally {
            msg.release()
        }
    }

    override fun flush(ctx: ChannelHandlerContext) {
        if (!closed && deflater.pendingPackets > 0) {
            try {
                emit(ctx)
            } catch (e: Throwable) {
                fail(ctx, e)
            }
        }
        ctx.flush()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        StreamStats.active.decrementAndGet()
        // 连接关闭时 netty 拆管线也会走这里,deflate 流占着原生内存,必须关
        if (closed) return
        closed = true
        deflater.close()
        failPromises(ClosedChannelException())
    }

    private fun passThrough(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (deflater.pendingPackets > 0) {
            try {
                emit(ctx)
            } catch (e: Throwable) {
                ReferenceCountUtil.release(msg)
                promise.tryFailure(e)
                fail(ctx, e)
                return
            }
        }
        ctx.write(msg, promise)
    }

    private fun emit(ctx: ChannelHandlerContext) {
        val start = System.nanoTime()
        val raw = deflater.pendingRaw
        val packets = deflater.pendingPackets
        val target = checkNotNull(ctx.pipeline().context(HandlerNames.PREPENDER)) {
            "NetSqueeze prepender disappeared"
        }
        val frame = deflater.finish() ?: return
        val size = frame.readableBytes().toLong()
        // prepender 的 context 往下写 = 从 prepender 的下一个(encrypt / socket 方向)开始,compress 和 prepender 都不经过
        target.write(frame, framePromise(ctx))
        pendingNanos += System.nanoTime() - start
        StreamStats.record(tally, raw, size, packets, pendingNanos)
        pendingNanos = 0
    }

    private fun emitZstd(ctx: ChannelHandlerContext, msg: ByteBuf, original: ChannelPromise): Boolean {
        val target = checkNotNull(ctx.pipeline().context(HandlerNames.PREPENDER)) {
            "NetSqueeze prepender disappeared"
        }
        val started = System.nanoTime()
        val frame = ZstdFrame.encode(ctx.alloc(), msg, zstdLevel) ?: return false
        val raw = VarInt.getByteSize(msg.readableBytes()).toLong() + msg.readableBytes()
        val frameBytes = frame.readableBytes().toLong()
        val promise = ctx.newPromise()
        promise.addListener { future ->
            if (original.isVoid) Unit
            else if (future.isSuccess) original.trySuccess()
            else original.tryFailure(future.cause() ?: ClosedChannelException())
            if (!future.isSuccess) fail(ctx, future.cause() ?: ClosedChannelException())
        }
        target.write(frame, promise)
        StreamStats.recordZstd(tally, raw, frameBytes, System.nanoTime() - started)
        return true
    }

    private fun framePromise(ctx: ChannelHandlerContext): ChannelPromise {
        val promise = ctx.newPromise()
        val pending = promises.toTypedArray()
        promises.clear()
        promise.addListener(PromiseNotifier<Void, ChannelFuture>(*pending))
        // 即使用 voidPromise 写原包，也不能忽略 socket/加密阶段的异步写失败。
        promise.addListener { future ->
            if (!future.isSuccess) fail(ctx, future.cause() ?: ClosedChannelException())
        }
        return promise
    }

    private fun fail(ctx: ChannelHandlerContext, e: Throwable) {
        if (closed) return
        closed = true
        deflater.close()
        failPromises(e)
        StreamStats.recordFailure(tally?.name ?: ctx.channel().remoteAddress().toString(), e)
        ctx.channel().close()
    }

    private fun failPromises(e: Throwable) {
        promises.forEach { it.tryFailure(e) }
        promises.clear()
    }
}
