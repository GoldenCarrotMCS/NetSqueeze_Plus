package me.albert.netsqueeze.tunnel

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import me.albert.netsqueeze.protocol.TunnelProtocol as Wire

/** encoder 尾侧、unbundler 头侧：看到拆开的原包；非白名单包保留给 VC 处理。 */
internal class TunnelGate(private val bridge: TunnelCompressor) : ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val previous = bridge.candidate
        try {
            val selected = bridge.enabled && Wire.allowed(msg.javaClass.name)
            if (!selected) bridge.emit() // 待发数据不能跨过原版控制包
            val name = msg.javaClass.name
            val startConfiguration = name == "net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket"
            if (startConfiguration || name == "net.minecraft.network.protocol.game.ClientboundRespawnPacket") {
                bridge.pause()
            }
            bridge.candidate = selected
            ctx.write(msg, promise)
            if (!startConfiguration && name.startsWith("net.minecraft.network.protocol.game.")) bridge.resumeAfterGamePacket()
        } catch (e: Throwable) {
            io.netty.util.ReferenceCountUtil.release(msg)
            promise.tryFailure(e)
            ctx.close()
        } finally { bridge.candidate = previous }
    }
}
