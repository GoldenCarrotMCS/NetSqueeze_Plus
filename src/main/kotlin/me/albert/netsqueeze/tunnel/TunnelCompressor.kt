package me.albert.netsqueeze.tunnel

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.PromiseCombiner
import me.albert.netsqueeze.protocol.DictionaryProtocol
import me.albert.netsqueeze.protocol.ZstdDictionary
import me.albert.netsqueeze.protocol.ChunkCacheProtocol
import me.albert.netsqueeze.protocol.SnapshotCodec
import me.albert.netsqueeze.config.Settings
import me.albert.netsqueeze.protocol.TunnelProtocol as Wire
import me.albert.netsqueeze.stats.PlayerTally
import me.albert.netsqueeze.stats.StreamStats
import me.albert.netsqueeze.stats.TunnelStats
import me.albert.netsqueeze.stream.FrameDeflater
import me.albert.netsqueeze.stream.ZstdFrame
import me.albert.netsqueeze.platform.VarInts as VarInt
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
    val protocol: Int = me.albert.netsqueeze.platform.ServerNetwork.protocolVersion()
    private val zstdAvailable = settings.zstdEnabled && ZstdFrame.available()
    private var markedFrames = true
    private var zstdEnabled = false
    var candidate = false
    var chunkCandidate = false
    private var snapshots: SnapshotCodec? = null
    private var cacheMode = false
    private var dictionary: ZstdDictionary? = null
    private val dictionaryAcks = arrayOfNulls<Any>(2)
    var snapshotBusy = false
        private set
    var internalEmission = false
        private set
    var gate: TunnelGate? = null
    private var asynchronousPromise: ChannelPromise? = null
    private var busyTimeout: java.util.concurrent.ScheduledFuture<*>? = null
    companion object {
        private val snapshotMemory = java.util.concurrent.atomic.AtomicLong()
        private val workers = java.util.concurrent.ThreadPoolExecutor(2, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "NetSqueeze-snapshot").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
        fun stopWorkers() { workers.shutdownNow() }
    }

    private fun writeCarrier(packet: Any): io.netty.channel.ChannelFuture {
        val previous = internalEmission
        internalEmission = true
        return try { context.channel().write(packet) } finally { internalEmission = previous }
    }

    fun dictionaryAck(cache: Boolean): Any {
        val index = if (cache) 1 else 0
        return dictionaryAcks[index] ?: me.albert.netsqueeze.platform.ServerNetwork.packetAccess()
            .id(DictionaryProtocol.ack(session, protocol, cache)).also { dictionaryAcks[index] = it }
    }

    fun cacheAck(zstd: Boolean): Any = me.albert.netsqueeze.platform.ServerNetwork.packetAccess().id(ChunkCacheProtocol.ack(session, protocol, zstd))

    fun cacheSeed(packetId: Any?): Boolean {
        val path = packetId?.toString() ?: return false
        val prefix = "netsqueeze:" + ChunkCacheProtocol.SEED + session + "/"
        if (!path.startsWith(prefix)) return false
        val hash = path.removePrefix(prefix)
        if (cacheMode && ChunkCacheProtocol.validHash(hash)) snapshots?.advertise(hash)
        return true
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) { context = ctx }

    fun ack(zstd: Boolean, marked: Boolean = true): Any = TunnelProtocol.ack(session, protocol, marked = marked, zstd = zstd)

    fun offer() {
        if (!closed && playing && !enabled) {
            if (settings.zstdDictionaryEnabled && zstdAvailable && ZstdFrame.dictionaryAvailable()) {
                val base = ChunkCacheProtocol.Offer(session, protocol, true, UUID.fromString(settings.chunkCacheNamespace), settings.chunkCacheEpoch)
                context.channel().write(TunnelProtocol.packet(me.albert.netsqueeze.platform.ServerNetwork.packetAccess().id(DictionaryProtocol.OFFER), DictionaryProtocol.encode(base, settings.chunkCacheEnabled)))
            }
            if (settings.chunkCacheEnabled) {
                val offer = ChunkCacheProtocol.Offer(session, protocol, zstdAvailable, UUID.fromString(settings.chunkCacheNamespace), settings.chunkCacheEpoch)
                context.channel().write(TunnelProtocol.packet(me.albert.netsqueeze.platform.ServerNetwork.packetAccess().id(ChunkCacheProtocol.OFFER), ChunkCacheProtocol.offer(offer)))
            }
            context.channel().write(TunnelProtocol.offer(session, protocol, marked = true, zstd = zstdAvailable))
            context.channel().writeAndFlush(TunnelProtocol.offer(session, protocol, marked = false, zstd = false))
        }
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

    fun acknowledge(useZstd: Boolean = false, marked: Boolean = true, cache: Boolean = false, useDictionary: Boolean = false) {
        if (closed || enabled || !playing || !context.channel().isActive) return
        if (useDictionary && (!useZstd || !marked || !zstdAvailable || !settings.zstdDictionaryEnabled || !ZstdFrame.dictionaryAvailable())) return
        if (cache && !settings.chunkCacheEnabled) return
        dictionary = if (useDictionary) ZstdDictionary() else null
        cacheMode = cache && marked && settings.chunkCacheEnabled
        if (cacheMode) snapshots = SnapshotCodec(snapshotMemory, 64L * 1024 * 1024)
        markedFrames = marked
        zstdEnabled = marked && useZstd && zstdAvailable
        deflater = FrameDeflater(context.alloc(), settings.compressionLevel, markedFrames)
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
        if (cacheMode && chunkCandidate && msg.readableBytes() in 128..ChunkCacheProtocol.MAX_PACKET) {
            val raw = ByteArray(msg.readableBytes())
            msg.getBytes(msg.readerIndex(), raw)
            val codec = checkNotNull(snapshots)
            snapshotBusy = true
            asynchronousPromise = promise
            val currentSession = session
            busyTimeout = ctx.executor().schedule({ if (snapshotBusy) fail(java.util.concurrent.TimeoutException("Snapshot worker timed out")) }, 2, java.util.concurrent.TimeUnit.SECONDS)
            try {
                workers.execute {
                    val before = longArrayOf(codec.full, codec.hits, codec.patches)
                    val result = runCatching { codec.encode(raw, true) }
                    try { ctx.executor().execute {
                        if (closed || failed || session != currentSession) {
                            promise.tryFailure(ClosedChannelException())
                        } else {
                            result.onSuccess { envelope ->
                                me.albert.netsqueeze.stats.ChunkCacheStats.full.add(codec.full - before[0])
                                me.albert.netsqueeze.stats.ChunkCacheStats.hits.add(codec.hits - before[1])
                                me.albert.netsqueeze.stats.ChunkCacheStats.patches.add(codec.patches - before[2])
                                compress(ctx, io.netty.buffer.Unpooled.wrappedBuffer(envelope), promise)
                            }.onFailure { promise.tryFailure(it); fail(it) }
                        }
                        busyTimeout?.cancel(false); busyTimeout = null
                        asynchronousPromise = null; snapshotBusy = false
                        if (!closed && !failed) gate?.drain()
                    } } catch (e: java.util.concurrent.RejectedExecutionException) { promise.tryFailure(e) }
                }
                msg.release()
                return
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                busyTimeout?.cancel(false); busyTimeout = null
                asynchronousPromise = null; snapshotBusy = false
                // No dictionary or snapshot mutation occurred: safely send full ordinary data.
            }
        }
        compress(ctx, msg, promise, wrap = cacheMode)
    }

    private fun compress(ctx: ChannelHandlerContext, msg: ByteBuf, promise: ChannelPromise, wrap: Boolean = false) {
        var input = msg
        try {
            if (wrap) {
                val raw = ByteArray(msg.readableBytes())
                msg.getBytes(msg.readerIndex(), raw)
                val codec = checkNotNull(snapshots)
                val oldFull = codec.full; val oldHits = codec.hits; val oldPatches = codec.patches
                input = io.netty.buffer.Unpooled.wrappedBuffer(codec.encode(raw, false))
                me.albert.netsqueeze.stats.ChunkCacheStats.full.add(codec.full - oldFull)
                me.albert.netsqueeze.stats.ChunkCacheStats.hits.add(codec.hits - oldHits)
                me.albert.netsqueeze.stats.ChunkCacheStats.patches.add(codec.patches - oldPatches)
            }
            if (!cacheMode && input.readableBytes() < settings.tunnelMinPacketBytes) {
                emit()
                // 没喂给 Deflater，字典没有推进，可安全保留原版小包，避免固定隧道头放大流量。
                ctx.write(input.retain(), promise)
                return
            }
            val compressor = checkNotNull(deflater)
            val size = input.readableBytes().toLong() + VarInt.getByteSize(input.readableBytes())
            require(input.isReadable && input.readableBytes() <= ZstdFrame.MAX_INPUT + 1)
            if (zstdEnabled && input.readableBytes() <= ZstdFrame.MAX_INPUT && input.readableBytes() >= settings.zstdMinPacketBytes) {
                if (compressor.pendingPackets > 0) emit()
                if (emitZstd(input, promise)) return
            }
            val limit = minOf(settings.maxFrameRawBytes, 512 * 1024)
            if (compressor.pendingPackets > 0 &&
                (compressor.pendingRaw + size > limit || compressor.pendingPackets >= 4096)) emit()
            compressor.feed(input)
            if (!promise.isVoid) pending += promise
            if (compressor.pendingRaw >= limit) emit()
        } catch (e: Throwable) {
            if (!promise.isVoid) promise.tryFailure(e)
            fail(e)
        } finally { if (input !== msg) input.release(); msg.release() }
    }

    fun emit() {
        val compressor = deflater ?: return
        if (!enabled || compressor.pendingPackets == 0) return
        val raw = compressor.pendingRaw
        val frame = compressor.finish() ?: return
        val originals = pending.toList()
        pending.clear()
        writeFrame(frame, raw, originals, zstd = false)
    }

    private fun emitZstd(msg: ByteBuf, promise: ChannelPromise): Boolean {
        val frame = try { ZstdFrame.encode(context.alloc(), msg, settings.zstdLevel, dictionary) }
        catch (_: LinkageError) { null }
        catch (_: RuntimeException) { null }
        if (frame == null) return false
        val raw = msg.readableBytes().toLong() + VarInt.getByteSize(msg.readableBytes())
        writeFrame(frame, raw, if (promise.isVoid) emptyList() else listOf(promise), zstd = true)
        return true
    }

    private fun writeFrame(frame: ByteBuf, raw: Long, originals: List<ChannelPromise>, zstd: Boolean) {
        val completion = context.newPromise()
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
                combined.add(writeCarrier(TunnelProtocol.packet(if (dictionary != null) me.albert.netsqueeze.platform.ServerNetwork.packetAccess().id(DictionaryProtocol.DATA) else if (cacheMode) me.albert.netsqueeze.platform.ServerNetwork.packetAccess().id(ChunkCacheProtocol.DATA) else TunnelProtocol.data(markedFrames), payload.array())))
                TunnelStats.payload.add(payload.array().size.toLong())
            }
            combined.finish(completion)
            tally.raw.add(raw)
            TunnelStats.raw.add(raw)
            TunnelStats.frames.increment()
            if (zstd) {
                TunnelStats.zstdRaw.add(raw)
                TunnelStats.zstdFrames.increment()
            }
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
        if (snapshotBusy) { fail(ClosedChannelException()); return }
        if (enabled) {
            emit()
            if (closed || !enabled) return
            val stop = ByteBuffer.allocate(16).putLong(session.mostSignificantBits).putLong(session.leastSignificantBits)
            writeCarrier(TunnelProtocol.packet(if (dictionary != null) me.albert.netsqueeze.platform.ServerNetwork.packetAccess().id(DictionaryProtocol.STOP) else if (cacheMode) me.albert.netsqueeze.platform.ServerNetwork.packetAccess().id(ChunkCacheProtocol.STOP) else TunnelProtocol.stop(markedFrames), stop.array()))
            enabled = false
            tally.tunnel = false
            TunnelStats.active.decrementAndGet()
            deflater?.close()
            deflater = null
            onStateChange(false)
        }
        dictionary?.close(); dictionary = null
        snapshots?.close(); snapshots = null; cacheMode = false
        playing = false
        session = UUID.randomUUID()
        dictionaryAcks.fill(null)
        sequence = 0
        reads = 0
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        if (closed) return
        closed = true
        dictionary?.close(); dictionary = null
        busyTimeout?.cancel(false); busyTimeout = null
        asynchronousPromise?.tryFailure(ClosedChannelException()); asynchronousPromise = null
        gate?.abort()
        snapshots?.close(); snapshots = null
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
