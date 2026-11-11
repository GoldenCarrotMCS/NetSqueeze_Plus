package me.albert.netsqueeze.tunnel

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.PromiseCombiner
import me.albert.netsqueeze.config.Settings
import me.albert.netsqueeze.protocol.TunnelProtocol as Wire
import me.albert.netsqueeze.stats.PlayerTally
import me.albert.netsqueeze.stats.StreamStats
import me.albert.netsqueeze.stats.TunnelStats
import me.albert.netsqueeze.stream.FrameDeflater
import net.minecraft.network.VarInt
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.UUID

/** 载荷重新走普通 PacketEncoder/compress/prepender，绝不向 VC 写裸 deflate。 */
internal class TunnelCompressor(
    private val settings: Settings,
    private val tally: PlayerTally,
    private val onStateChange: (Boolean) -> Unit
) : ChannelOutboundHandlerAdapter() {
    private lateinit var context: ChannelHandlerContext
    private var deflater: FrameDeflater? = null
    private val pending = ArrayList<ChannelPromise>()
    private var sequence = 0L
    private var closed = false
    private var failed = false
    private var playing = true
    private var reads = 0
    var session: UUID = UUID.randomUUID()
        private set
    @Volatile
    var enabled = false
        private set
    var candidate = false

    override fun handlerAdded(ctx: ChannelHandlerContext) { context = ctx }

    fun offer() {
        if (!closed && playing && !enabled) context.channel().writeAndFlush(TunnelProtocol.offer(session))
    }

    fun inboundPlayPacket() {
        if (!playing) return
        reads++
        // 只在确实有 PLAY 入站时限次重试，不能在代理尚无后端时依赖一次早发 hello。
        if (reads == 1 || reads == 20 || reads == 100) offer()
    }

    fun resumeAfterGamePacket() {
        if (!closed && !playing) { playing = true; offer() }
    }

    fun acknowledge() {
        if (closed || enabled || !playing || !context.channel().isActive) return
        deflater = FrameDeflater(context.alloc(), settings.compressionLevel)
        enabled = true
        tally.tunnel = true
        tally.raw.reset()
        TunnelStats.active.incrementAndGet()
        onStateChange(true)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (closed) {
            ReferenceCountUtil.release(msg)
            promise.tryFailure(ClosedChannelException())
            return
        }
        if (!enabled || !candidate || msg !is ByteBuf) {
            ctx.write(msg, promise)
            return
        }
        try {
            if (msg.readableBytes() < settings.tunnelMinPacketBytes) {
                emit()
                // 没喂给 Deflater，字典没有推进，可安全保留原版小包，避免固定隧道头放大流量。
                ctx.write(msg.retain(), promise)
                return
            }
            val compressor = checkNotNull(deflater)
            val size = msg.readableBytes().toLong() + VarInt.getByteSize(msg.readableBytes())
            require(msg.isReadable && msg.readableBytes() <= 8 * 1024 * 1024)
            val limit = minOf(settings.maxFrameRawBytes, 512 * 1024)
            if (compressor.pendingPackets > 0 &&
                (compressor.pendingRaw + size > limit || compressor.pendingPackets >= 4096)) emit()
            compressor.feed(msg)
            if (!promise.isVoid) pending += promise
            if (compressor.pendingRaw >= limit) emit()
        } catch (e: Throwable) {
            if (!promise.isVoid) promise.tryFailure(e)
            fail(e)
        } finally { msg.release() }
    }

    fun emit() {
        val compressor = deflater ?: return
        if (!enabled || compressor.pendingPackets == 0) return
        val raw = compressor.pendingRaw
        val frame = compressor.finish() ?: return
        val completion = context.newPromise()
        val originals = pending.toList()
        pending.clear()
        completion.addListener { future ->
            originals.forEach {
                if (future.isSuccess) it.trySuccess() else it.tryFailure(future.cause() ?: ClosedChannelException())
            }
            if (!future.isSuccess) fail(future.cause() ?: ClosedChannelException())
        }
        try {
            val combined = PromiseCombiner(context.executor())
            while (frame.isReadable) {
                val length = minOf(Wire.FRAGMENT_BYTES, frame.readableBytes())
                val payload = ByteBuffer.allocate(Wire.HEADER_BYTES + length)
                    .putLong(session.mostSignificantBits).putLong(session.leastSignificantBits).putLong(sequence)
                sequence = Math.incrementExact(sequence)
                frame.readBytes(payload.array(), Wire.HEADER_BYTES, length)
                combined.add(context.channel().write(TunnelProtocol.packet(TunnelProtocol.DATA, payload.array())))
                TunnelStats.payload.add(payload.array().size.toLong())
            }
            combined.finish(completion)
            tally.raw.add(raw)
            TunnelStats.raw.add(raw)
            TunnelStats.frames.increment()
        } catch (e: Throwable) {
            completion.tryFailure(e)
            throw e
        } finally { frame.release() }
    }

    override fun flush(ctx: ChannelHandlerContext) {
        try { emit() } catch (e: Throwable) { fail(e) }
        ctx.flush()
    }

    /** 重配置与卸载先收尾再发 STOP，客户端不需要断线也能恢复纯原版。 */
    fun pause() {
        if (enabled) {
            emit()
            if (closed || !enabled) return
            val stop = ByteBuffer.allocate(16).putLong(session.mostSignificantBits).putLong(session.leastSignificantBits)
            context.channel().write(TunnelProtocol.packet(TunnelProtocol.STOP, stop.array()))
            enabled = false
            tally.tunnel = false
            TunnelStats.active.decrementAndGet()
            deflater?.close()
            deflater = null
            onStateChange(false)
        }
        playing = false
        session = UUID.randomUUID()
        sequence = 0
        reads = 0
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        if (closed) return
        closed = true
        if (enabled) TunnelStats.active.decrementAndGet()
        enabled = false
        tally.tunnel = false
        deflater?.close()
        pending.forEach { it.tryFailure(ClosedChannelException()) }
        pending.clear()
    }

    private fun fail(e: Throwable) {
        if (closed || failed) return
        failed = true
        TunnelStats.failures.increment()
        StreamStats.recordFailure(tally.name, e)
        context.close()
    }
}
