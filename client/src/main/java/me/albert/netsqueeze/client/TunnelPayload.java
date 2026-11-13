package me.albert.netsqueeze.client;

import io.netty.handler.codec.CorruptedFrameException;
import me.albert.netsqueeze.protocol.TunnelProtocol;
import me.albert.netsqueeze.protocol.ChunkCacheProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 原版未知载荷会丢数据；只为本协议提供自己的 Codec，不需要 Fabric API。 */
public record TunnelPayload(Identifier id, byte[] data) implements CustomPacketPayload {
    public static final Identifier OFFER = Identifier.fromNamespaceAndPath("netsqueeze", TunnelProtocol.OFFER_PATH);
    public static final Identifier DATA = Identifier.fromNamespaceAndPath("netsqueeze", TunnelProtocol.DATA_PATH);
    public static final Identifier STOP = Identifier.fromNamespaceAndPath("netsqueeze", TunnelProtocol.STOP_PATH);

    public static final Identifier LEGACY_OFFER = Identifier.fromNamespaceAndPath("netsqueeze", TunnelProtocol.offerPath(false));
    public static final Identifier LEGACY_DATA = Identifier.fromNamespaceAndPath("netsqueeze", TunnelProtocol.dataPath(false));
    public static final Identifier LEGACY_STOP = Identifier.fromNamespaceAndPath("netsqueeze", TunnelProtocol.stopPath(false));

    public static final Identifier CACHE_OFFER = Identifier.fromNamespaceAndPath("netsqueeze", ChunkCacheProtocol.OFFER);
    public static final Identifier CACHE_DATA = Identifier.fromNamespaceAndPath("netsqueeze", ChunkCacheProtocol.DATA);
    public static final Identifier CACHE_STOP = Identifier.fromNamespaceAndPath("netsqueeze", ChunkCacheProtocol.STOP);

    @Override public Type<TunnelPayload> type() { return new Type<>(id); }

    public static <B extends FriendlyByteBuf> CustomPacketPayload.FallbackProvider<B> fallback(
            CustomPacketPayload.FallbackProvider<B> original) {
        return id -> id.equals(OFFER) || id.equals(DATA) || id.equals(STOP) ||
            id.equals(LEGACY_OFFER) || id.equals(LEGACY_DATA) || id.equals(LEGACY_STOP) ||
            id.equals(CACHE_OFFER) || id.equals(CACHE_DATA) || id.equals(CACHE_STOP)
            ? codec(id) : original.create(id);
    }

    public static <B extends FriendlyByteBuf> StreamCodec<B, TunnelPayload> codec(Identifier id) {
        return new StreamCodec<>() {
            @Override public TunnelPayload decode(B buf) {
                int size = buf.readableBytes();
                if (size > TunnelProtocol.HEADER_BYTES + TunnelProtocol.FRAGMENT_BYTES) {
                    throw new CorruptedFrameException("NetSqueeze 隧道载荷过大");
                }
                byte[] bytes = new byte[size];
                buf.readBytes(bytes);
                return new TunnelPayload(id, bytes);
            }
            @Override public void encode(B buf, TunnelPayload value) { buf.writeBytes(value.data()); }
        };
    }
}
