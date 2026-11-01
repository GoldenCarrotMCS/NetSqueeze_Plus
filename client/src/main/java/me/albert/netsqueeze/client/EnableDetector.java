package me.albert.netsqueeze.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;

/**
 * 挂在 packet_handler 前面,看的是解好的包对象,和 {@link StreamDecoder} 同一次 channelRead 里同步跑:
 * 解码器交出 enable 这一帧、等它返回时,这里已经置好位,解码器接着就按新格式解剩下的字节。
 * <ul>
 *   <li>收到 {@code netsqueeze:enable}:通知解码器切换,吞掉这个包,摘掉自己;</li>
 *   <li>配置阶段结束还没等到:服务端没装插件,通知解码器撤掉,摘掉自己。</li>
 * </ul>
 */
final class EnableDetector extends ChannelInboundHandlerAdapter {

    static final String NAME = "netsqueeze_detect";

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof ClientboundCustomPayloadPacket packet && packet.payload().type().id().equals(NetSqueezeClient.ENABLE)) {
            final StreamDecoder decoder = decoder(ctx);
            ctx.pipeline().remove(this);
            if (decoder == null) {
                // 不该发生(没解码器就不会发 hello);服务端已经切了,这边解不了,只能断开
                throw new IllegalStateException("NetSqueeze: 收到 enable 但解码器不在管线里");
            }
            decoder.enable();
            NetSqueezeClient.LOGGER.info("服务器已启用流式压缩");
            return;
        }
        if (msg instanceof ClientboundFinishConfigurationPacket) {
            final StreamDecoder decoder = decoder(ctx);
            ctx.pipeline().remove(this);
            if (decoder != null) decoder.giveUp();
        }
        ctx.fireChannelRead(msg);
    }

    private static StreamDecoder decoder(final ChannelHandlerContext ctx) {
        return (StreamDecoder) ctx.pipeline().get(StreamDecoder.NAME);
    }
}
