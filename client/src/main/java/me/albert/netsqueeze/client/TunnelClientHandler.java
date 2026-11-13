package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.CorruptedFrameException;
import me.albert.netsqueeze.client.mixin.PacketDecoderAccessor;
import me.albert.netsqueeze.protocol.ChunkCacheProtocol;
import me.albert.netsqueeze.protocol.SnapshotCodec;
import me.albert.netsqueeze.protocol.TunnelProtocol;
import me.albert.netsqueeze.protocol.MinecraftVersions;
import net.minecraft.SharedConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Decoder -> this handler -> bundler: no recursive ByteToMessageDecoder invocation. */
final class TunnelClientHandler extends ChannelInboundHandlerAdapter {
    static final String NAME = "netsqueeze_tunnel_client";
    private TunnelReassembler receiver;
    private SnapshotCodec snapshots;
    private ClientChunkCache.Lease disk;
    private UUID preparing;
    private ScheduledFuture<?> prepareTimeout;
    private long generation;
    private boolean confirmed;

    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        String name = msg.getClass().getName();
        if (name.equals("net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket") ||
                name.equals("net.minecraft.network.protocol.game.ClientboundLoginPacket") ||
                name.equals("net.minecraft.network.protocol.game.ClientboundRespawnPacket")) reset();
        if (!(msg instanceof ClientboundCustomPayloadPacket packet) || !(packet.payload() instanceof TunnelPayload payload)) {
            ctx.fireChannelRead(msg); return;
        }
        try {
            if (payload.id().equals(TunnelPayload.CACHE_OFFER)) {
                ChunkCacheProtocol.Offer offer = ChunkCacheProtocol.offer(payload.data());
                if (!eligible(ctx, offer.protocol())) return;
                if (receiver != null) {
                    if (receiver.cache && receiver.session.equals(offer.session())) ack(ctx, ChunkCacheProtocol.ack(receiver.session, offer.protocol(), receiver.zstdEnabled));
                    return;
                }
                if (preparing != null) return;
                preparing = offer.session(); long token = ++generation;
                // No ACK until disk seeds are pinned. Original traffic continues during preparation.
                prepareTimeout = ctx.executor().schedule(() -> {
                    if (generation == token && preparing != null) {
                        preparing = null; generation++;
                        beginLegacy(ctx, offer.session(), true, offer.zstd());
                    }
                }, 2, TimeUnit.SECONDS);
                Minecraft.getInstance().execute(() -> ClientChunkCache.prepare(offer, prepared -> {
                    try { ctx.executor().execute(() -> complete(ctx, offer, prepared, token)); }
                    catch (java.util.concurrent.RejectedExecutionException ignored) { }
                }));
            } else if (payload.id().equals(TunnelPayload.OFFER) || payload.id().equals(TunnelPayload.LEGACY_OFFER)) {
                TunnelProtocol.Offer offer = TunnelProtocol.decodeOffer(payload.data());
                boolean marked = payload.id().equals(TunnelPayload.OFFER);
                if (offer.markedFrames() != marked) throw new CorruptedFrameException("Tunnel offer channel/format mismatch");
                if (preparing != null) return;
                if (receiver != null) {
                    if (!receiver.cache && receiver.session.equals(offer.session()) && receiver.markedFrames == marked)
                        ack(ctx, TunnelProtocol.ackPath(receiver.session, offer.protocol(), marked, receiver.zstdEnabled));
                    return;
                }
                if (eligible(ctx, offer.protocol())) beginLegacy(ctx, offer.session(), marked, offer.zstd());
            } else if (payload.id().equals(TunnelPayload.STOP) || payload.id().equals(TunnelPayload.LEGACY_STOP) || payload.id().equals(TunnelPayload.CACHE_STOP)) {
                if (receiver != null && receiver.session.equals(session(payload.data())) &&
                        receiver.cache == payload.id().equals(TunnelPayload.CACHE_STOP) &&
                        (receiver.cache || receiver.markedFrames == payload.id().equals(TunnelPayload.STOP))) reset();
            } else if (receiver != null) {
                Identifier expected = receiver.cache ? TunnelPayload.CACHE_DATA : receiver.markedFrames ? TunnelPayload.DATA : TunnelPayload.LEGACY_DATA;
                if (!payload.id().equals(expected)) throw new CorruptedFrameException("Tunnel data channel differs from negotiated format");
                receiver.accept(ctx.alloc(), payload.data(), bytes -> decodeInner(ctx, bytes));
            }
        } catch (Exception e) {
            reset(); ctx.close(); throw e;
        }
    }
    private void complete(ChannelHandlerContext ctx, ChunkCacheProtocol.Offer offer, ClientChunkCache.Prepared prepared, long token) {
        if (generation != token || preparing == null || !ctx.channel().isActive()) return;
        if (prepareTimeout != null) prepareTimeout.cancel(false);
        prepareTimeout = null; preparing = null;
        if (prepared == null || !ClientChunkCache.valid(prepared)) { beginLegacy(ctx, offer.session(), true, offer.zstd()); return; }
        boolean useZstd = offer.zstd() && ZstdSupport.available();
        receiver = new TunnelReassembler(offer.session(), true, useZstd, true);
        snapshots = new SnapshotCodec();
        disk = ClientChunkCache.activate(prepared);
        for (byte[] seed : prepared.seeds()) snapshots.pin(seed);
        ack(ctx, ChunkCacheProtocol.ack(offer.session(), offer.protocol(), useZstd));
        for (byte[] seed : prepared.seeds()) ack(ctx, ChunkCacheProtocol.seed(offer.session(), ChunkCacheProtocol.hash(seed)));
        ClientChunkCache.message("区块缓存 v4 已协商；种子 " + prepared.seeds().size() + " 个。未变复用、小变化补丁、其他完整发送。/nsqcache deny 可撤回磁盘授权。");
    }
    private void beginLegacy(ChannelHandlerContext ctx, UUID session, boolean marked, boolean zstd) {
        if (receiver != null || !ctx.channel().isActive()) return;
        boolean useZstd = marked && zstd && ZstdSupport.available();
        receiver = new TunnelReassembler(session, marked, useZstd);
        ack(ctx, TunnelProtocol.ackPath(session, SharedConstants.getProtocolVersion(), marked, useZstd));
        NetSqueezeClient.LOGGER.info("GoldenCarrot服务器兼容隧道已协商，ZSTD={}，持久区块缓存=false", useZstd);
    }
    private static void ack(ChannelHandlerContext ctx, String path) {
        Identifier id = Identifier.fromNamespaceAndPath("netsqueeze", path);
        ctx.channel().writeAndFlush(new ServerboundCustomPayloadPacket(new DiscardedPayload(id)));
    }
    private static boolean eligible(ChannelHandlerContext ctx, int serverProtocol) {
        String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
        return MinecraftVersions.eligible(version) && MinecraftVersions.protocolMatches(serverProtocol, SharedConstants.getProtocolVersion()) &&
            ctx.pipeline().get(HandlerNames.DECODER) instanceof PacketDecoderAccessor && protocol(ctx).id() == ConnectionProtocol.PLAY;
    }
    private static UUID session(byte[] bytes) {
        if (bytes.length != 16) throw new CorruptedFrameException("Invalid STOP session size");
        ByteBuffer data = ByteBuffer.wrap(bytes); return new UUID(data.getLong(), data.getLong());
    }
    private static ProtocolInfo<?> protocol(ChannelHandlerContext ctx) {
        if (!(ctx.pipeline().get(HandlerNames.DECODER) instanceof PacketDecoderAccessor accessor))
            throw new CorruptedFrameException("Missing packet decoder accessor");
        return accessor.netsqueeze$getProtocolInfo();
    }
    private void decodeInner(ChannelHandlerContext ctx, ByteBuf bytes) {
        ByteBuf original = bytes;
        try {
            byte[] snapshot = null;
            if (snapshots != null) {
                byte[] envelope = new byte[bytes.readableBytes()]; bytes.readBytes(envelope);
                SnapshotCodec.Decoded restored = snapshots.decode(envelope);
                bytes = Unpooled.wrappedBuffer(restored.packet());
                if (restored.snapshot()) snapshot = restored.packet();
            }
            ProtocolInfo<?> info = protocol(ctx);
            if (info.id() != ConnectionProtocol.PLAY) throw new CorruptedFrameException("Tunnel crossed protocol state");
            Packet<?> decoded = info.codec().decode(bytes);
            if (bytes.isReadable() || !TunnelProtocol.allowed(decoded.getClass().getName())) throw new CorruptedFrameException("Invalid inner packet");
            if (snapshot != null) {
                if (!decoded.getClass().getName().equals("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket"))
                    throw new CorruptedFrameException("Snapshot is not a chunk packet");
                if (disk != null) disk.save(snapshot);
            }
            ctx.fireChannelRead(decoded);
            if (!confirmed) { confirmed = true; NetSqueezeClient.LOGGER.info("压缩隧道已恢复首个包；/nsq 可查看载荷统计，非 VC 出口实测。"); }
        } finally { if (bytes != original) bytes.release(); original.release(); }
    }
    private void reset() {
        generation++; preparing = null;
        if (prepareTimeout != null) { prepareTimeout.cancel(false); prepareTimeout = null; }
        if (receiver != null) { receiver.close(); receiver = null; }
        if (snapshots != null) { snapshots.close(); snapshots = null; }
        if (disk != null) { disk.close(); disk = null; }
        confirmed = false;
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) { reset(); ctx.fireChannelInactive(); }
    @Override public void handlerRemoved(ChannelHandlerContext ctx) { reset(); }
}
