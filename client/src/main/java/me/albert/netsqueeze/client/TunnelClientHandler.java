package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.CorruptedFrameException;
import me.albert.netsqueeze.client.mixin.PacketDecoderAccessor;
import me.albert.netsqueeze.protocol.TunnelProtocol;
import me.albert.netsqueeze.protocol.MinecraftVersions;
import net.minecraft.SharedConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import java.nio.ByteBuffer;
import java.util.UUID;

/** 必须位于 decoder 之后、bundler 之前；不递归进入 ByteToMessageDecoder。 */
final class TunnelClientHandler extends ChannelInboundHandlerAdapter {
    static final String NAME = "netsqueeze_tunnel_client";
    private TunnelReassembler receiver;
    private boolean confirmed;

    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg.getClass().getName().equals("net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket") ||
                msg.getClass().getName().equals("net.minecraft.network.protocol.game.ClientboundLoginPacket") ||
                msg.getClass().getName().equals("net.minecraft.network.protocol.game.ClientboundRespawnPacket")) reset();
        if (!(msg instanceof ClientboundCustomPayloadPacket packet) || !(packet.payload() instanceof TunnelPayload payload)) {
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            if (payload.id().equals(TunnelPayload.OFFER)) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
                if (!MinecraftVersions.eligible(version)) return;
                TunnelProtocol.Offer offer = TunnelProtocol.decodeOffer(payload.data());
                int localProtocol = SharedConstants.getProtocolVersion();
                if (!MinecraftVersions.protocolMatches(offer.protocol(), localProtocol)) {
                    NetSqueezeClient.LOGGER.info("代理隧道保持原版：后端协议 {} 与客户端协议 {} 不同，不能绕过版本转换", offer.protocol(), localProtocol);
                    return;
                }
                if (!(ctx.pipeline().get(HandlerNames.DECODER) instanceof PacketDecoderAccessor)) {
                    NetSqueezeClient.LOGGER.warn("NetSqueeze 隧道未启用：当前解码器不是已验证的原版实现");
                    return;
                }
                if (protocol(ctx).id() != ConnectionProtocol.PLAY) return;
                UUID session = offer.session();
                if (receiver == null || !receiver.session.equals(session)) {
                    reset();
                    receiver = new TunnelReassembler(session);
                }
                Identifier ack = Identifier.fromNamespaceAndPath("netsqueeze", TunnelProtocol.ackPath(session, localProtocol));
                ctx.channel().writeAndFlush(new ServerboundCustomPayloadPacket(new DiscardedPayload(ack)));
                NetSqueezeClient.LOGGER.info("GoldenCarrot服务器兼容模式：已协商 Velocity 压缩隧道（无需代理插件）");
            } else if (payload.id().equals(TunnelPayload.STOP)) {
                if (receiver != null && receiver.session.equals(session(payload.data()))) reset();
            } else if (receiver != null) {
                receiver.accept(ctx.alloc(), payload.data(), bytes -> decodeInner(ctx, bytes));
            }
        } catch (Exception e) {
            reset();
            ctx.close();
            throw e;
        }
    }

    private static UUID session(byte[] bytes) {
        if (bytes.length != 16) throw new CorruptedFrameException("NetSqueeze 隧道会话长度错误");
        ByteBuffer data = ByteBuffer.wrap(bytes);
        return new UUID(data.getLong(), data.getLong());
    }

    private static ProtocolInfo<?> protocol(ChannelHandlerContext ctx) {
        Object decoder = ctx.pipeline().get(HandlerNames.DECODER);
        if (!(decoder instanceof PacketDecoderAccessor accessor)) {
            throw new CorruptedFrameException("NetSqueeze 缺少已验证的包解码器访问器");
        }
        return accessor.netsqueeze$getProtocolInfo();
    }

    private void decodeInner(ChannelHandlerContext ctx, ByteBuf bytes) {
        try {
            ProtocolInfo<?> info = protocol(ctx);
            if (info.id() != ConnectionProtocol.PLAY) throw new CorruptedFrameException("NetSqueeze 隧道跨越协议状态");
            Packet<?> decoded = info.codec().decode(bytes);
            if (bytes.isReadable() || !TunnelProtocol.allowed(decoded.getClass().getName())) {
                throw new CorruptedFrameException("NetSqueeze 隧道包含非白名单包或多余数据");
            }
            ctx.fireChannelRead(decoded);
            if (!confirmed) {
                confirmed = true;
                NetSqueezeClient.LOGGER.info("Velocity 压缩隧道已恢复首个数据包，可在 /nsq 查看实际载荷统计");
            }
        } finally { bytes.release(); }
    }

    private void reset() { if (receiver != null) { receiver.close(); receiver = null; } confirmed = false; }
    @Override public void channelInactive(ChannelHandlerContext ctx) { reset(); ctx.fireChannelInactive(); }
    @Override public void handlerRemoved(ChannelHandlerContext ctx) { reset(); }
}
