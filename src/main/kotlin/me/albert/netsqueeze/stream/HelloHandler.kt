package me.albert.netsqueeze.stream

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import me.albert.netsqueeze.netty.Injector
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.DiscardedPayload

/**
 * 等客户端 mod 的握手:从连接建立起挂在 packet_handler 前面(看到的是解好的包对象),
 * 收到 [Protocol.HELLO] 就吞掉、摘掉自己,版本对得上再交给 [Injector.enableStream] 切换。
 * 没装 mod 的连接进服时由 Injector 摘掉,之前每个入站包只多一次 instanceof。
 */
internal class HelloHandler : ChannelInboundHandlerAdapter() {

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val payload = (msg as? ServerboundCustomPayloadPacket)?.payload() as? DiscardedPayload
        if (payload == null || payload.id() != Protocol.HELLO) {
            ctx.fireChannelRead(msg)
            return
        }
        ctx.pipeline().remove(this)
        if (payload.data().firstOrNull()?.toInt() != Protocol.VERSION) return
        // 这里就在这条连接的 netty 线程上:回 enable 和挂压缩之间不会插进别的出站字节
        Injector.enableStream(ctx.channel())
    }
}
