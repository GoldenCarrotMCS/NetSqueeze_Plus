package me.albert.netsqueeze.tunnel

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import me.albert.netsqueeze.protocol.TunnelProtocol as Wire

/** encoder 尾侧、unbundler 头侧：看到拆开的原包；非白名单包保留给 VC 处理。 */
internal class TunnelGate(private val bridge: TunnelCompressor) : ChannelOutboundHandlerAdapter() {
    private data class Deferred(val msg: Any?, val promise: ChannelPromise?)
    private val deferred = java.util.ArrayDeque<Deferred>()
    private lateinit var context: ChannelHandlerContext
    override fun handlerAdded(ctx: ChannelHandlerContext) { context = ctx; bridge.gate = this }
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (bridge.snapshotBusy && !bridge.internalEmission) {
            if (deferred.size >= 128) {
                io.netty.util.ReferenceCountUtil.release(msg)
                promise.tryFailure(IllegalStateException("Snapshot ordering queue full"))
                ctx.close()
            } else deferred.addLast(Deferred(msg, promise))
            return
        }
        val previous = bridge.candidate
        val previousChunk = bridge.chunkCandidate
        try {
            val selected = bridge.enabled && Wire.allowed(msg.javaClass.name)
            if (!selected) bridge.emit() // 待发数据不能跨过原版控制包
            val name = msg.javaClass.name
            val startConfiguration = name == "net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket"
            if (startConfiguration || name == "net.minecraft.network.protocol.game.ClientboundRespawnPacket") {
                bridge.pause()
            }
            bridge.candidate = selected
            bridge.chunkCandidate = name == "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket"
            ctx.write(msg, promise)
            if (!startConfiguration && name.startsWith("net.minecraft.network.protocol.game.")) bridge.resumeAfterGamePacket()
        } catch (e: Throwable) {
            io.netty.util.ReferenceCountUtil.release(msg)
            promise.tryFailure(e)
            ctx.close()
        } finally { bridge.candidate = previous; bridge.chunkCandidate = previousChunk }
    }
    override fun flush(ctx: ChannelHandlerContext) {
        if (bridge.snapshotBusy && !bridge.internalEmission) {
            if (deferred.peekLast()?.msg != null || deferred.isEmpty()) deferred.addLast(Deferred(null, null))
        } else ctx.flush()
    }
    fun drain() {
        while (!bridge.snapshotBusy && deferred.isNotEmpty() && context.channel().isActive) {
            val item = deferred.removeFirst()
            if (item.msg == null) context.flush() else write(context, item.msg, checkNotNull(item.promise))
        }
    }
    fun abort() {
        while (deferred.isNotEmpty()) {
            val item = deferred.removeFirst()
            io.netty.util.ReferenceCountUtil.release(item.msg)
            item.promise?.tryFailure(java.nio.channels.ClosedChannelException())
        }
    }
    override fun handlerRemoved(ctx: ChannelHandlerContext) { abort(); if (bridge.gate === this) bridge.gate = null }
}
