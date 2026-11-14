package me.albert.netsqueeze.tunnel

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import me.albert.netsqueeze.platform.ServerNetwork

internal class TunnelHandshake(private val bridge: TunnelCompressor) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val packetId = ServerNetwork.packetAccess().payloadId(msg)
        // Movement and other ordinary packets cannot be ACKs. Avoid all identifier
        // construction while preserving PLAY retry accounting and normal forwarding.
        if (packetId != null) {
            if (packetId == bridge.dictionaryAck(false)) { bridge.acknowledge(useZstd = true, useDictionary = true); return }
            if (packetId == bridge.dictionaryAck(true)) { bridge.acknowledge(useZstd = true, cache = true, useDictionary = true); return }
            if (packetId == bridge.cacheAck(true)) { bridge.acknowledge(useZstd = true, cache = true); return }
            if (packetId == bridge.cacheAck(false)) { bridge.acknowledge(useZstd = false, cache = true); return }
            if (bridge.cacheSeed(packetId)) return
            if (packetId == bridge.ack(true)) {
                bridge.acknowledge(useZstd = true)
                return
            }
            if (packetId == bridge.ack(false)) {
                bridge.acknowledge(useZstd = false)
                return
            }
            if (packetId == bridge.ack(zstd = false, marked = false)) {
                bridge.acknowledge(useZstd = false, marked = false)
                return
            }
        }
        if (msg.javaClass.name.startsWith("net.minecraft.network.protocol.game.Serverbound")) bridge.inboundPlayPacket()
        ctx.fireChannelRead(msg)
    }
}
