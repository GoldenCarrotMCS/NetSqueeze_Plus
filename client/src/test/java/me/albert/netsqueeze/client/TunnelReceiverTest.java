package me.albert.netsqueeze.client;

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.CorruptedFrameException;
import me.albert.netsqueeze.protocol.TunnelProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TunnelReceiverTest {
    private static List<byte[]> fixture(String name) throws Exception {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(Path.of(System.getProperty("netsqueeze.fixtures"), name)))) {
            List<byte[]> records = new ArrayList<>();
            int count = in.readInt();
            for (int i = 0; i < count; i++) records.add(in.readNBytes(in.readInt()));
            return records;
        }
    }

    @Test
    void actualServerCarriersDecodeWithoutAProxyExtension() throws Exception {
        List<byte[]> expected = fixture("tunnel-expected.bin");
        for (int level : new int[]{0, 6, 9}) {
            List<byte[]> carriers = fixture("tunnel-" + level + ".bin");
            ByteBuffer header = ByteBuffer.wrap(carriers.getFirst());
            UUID session = new UUID(header.getLong(), header.getLong());
            List<byte[]> actual = new ArrayList<>();
            try (TunnelReassembler receiver = new TunnelReassembler(session, true, true)) {
                for (byte[] carrier : carriers) {
                    assertTrue(carrier.length <= TunnelProtocol.HEADER_BYTES + TunnelProtocol.FRAGMENT_BYTES);
                    // 模拟代理只转发原版 CustomPayload 的数据；实际 VC/Fabric 启动仍须另测。
                    receiver.accept(UnpooledByteBufAllocator.DEFAULT, carrier, packet -> {
                        try {
                            byte[] data = new byte[packet.readableBytes()];
                            packet.readBytes(data);
                            actual.add(data);
                        } finally { packet.release(); }
                    });
                }
            }
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i));
        }
    }

    @Test
    void closingDuringDeliveryDoesNotDoubleReleaseCurrentFrame() throws Exception {
        List<byte[]> carriers = fixture("tunnel-6.bin");
        ByteBuffer header = ByteBuffer.wrap(carriers.getFirst());
        UUID session = new UUID(header.getLong(), header.getLong());
        boolean[] delivered = {false};
        try (TunnelReassembler receiver = new TunnelReassembler(session, true, true)) {
            for (byte[] carrier : carriers) {
                receiver.accept(UnpooledByteBufAllocator.DEFAULT, carrier, packet -> {
                    packet.release();
                    delivered[0] = true;
                    receiver.close();
                });
                if (delivered[0]) break;
            }
        }
        assertTrue(delivered[0]);
    }

    @Test
    void staleSessionIsIgnoredAndMissingSequenceIsRejected() throws Exception {
        UUID session = UUID.randomUUID();
        try (TunnelReassembler receiver = new TunnelReassembler(session, true, true)) {
            receiver.accept(UnpooledByteBufAllocator.DEFAULT, fragment(UUID.randomUUID(), 99, new byte[]{1}), packet -> fail());
            assertThrows(CorruptedFrameException.class, () ->
                receiver.accept(UnpooledByteBufAllocator.DEFAULT, fragment(session, 1, new byte[]{1}), packet -> fail()));
        }
    }

    @Test
    void rejectsHugeDeclarationBeforeFrameBodyAndRejectsDuplicateFragment() throws Exception {
        UUID session = UUID.randomUUID();
        try (TunnelReassembler receiver = new TunnelReassembler(session, true, true)) {
            assertThrows(CorruptedFrameException.class, () -> receiver.accept(UnpooledByteBufAllocator.DEFAULT,
                fragment(session, 0, new byte[]{-1, -1, -1, 127}), packet -> fail()));
        }
        try (TunnelReassembler receiver = new TunnelReassembler(session, true, true)) {
            byte[] incomplete = fragment(session, 0, new byte[]{100, 1});
            receiver.accept(UnpooledByteBufAllocator.DEFAULT, incomplete, packet -> fail());
            assertThrows(CorruptedFrameException.class, () ->
                receiver.accept(UnpooledByteBufAllocator.DEFAULT, incomplete, packet -> fail()));
        }
    }

    @Test
    void fallbackCodecKeepsOurBytesAndLeavesOtherChannelsAlone() {
        CustomPacketPayload.FallbackProvider<FriendlyByteBuf> fallback = TunnelPayload.fallback(
            id -> DiscardedPayload.codec(id, 1048576));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
        try {
            CustomPacketPayload payload = fallback.create(TunnelPayload.DATA).decode(buf);
            assertArrayEquals(new byte[]{1, 2, 3}, assertInstanceOf(TunnelPayload.class, payload).data());
        } finally { buf.release(); }
        FriendlyByteBuf unknown = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[]{7}));
        try {
            assertInstanceOf(DiscardedPayload.class,
                fallback.create(Identifier.fromNamespaceAndPath("other", "payload")).decode(unknown));
        } finally { unknown.release(); }
    }

    @Test
    void tunnelOfferNegotiatesProtocolInsteadOfMinecraftVersionString() {
        UUID session = UUID.randomUUID();
        int protocol = net.minecraft.SharedConstants.getProtocolVersion();
        TunnelProtocol.Offer offer = TunnelProtocol.decodeOffer(TunnelProtocol.encodeOffer(session, protocol));
        assertEquals(session, offer.session());
        assertTrue(me.albert.netsqueeze.protocol.MinecraftVersions.protocolMatches(offer.protocol(), protocol));
        assertFalse(me.albert.netsqueeze.protocol.MinecraftVersions.protocolMatches(offer.protocol(), protocol + 1));
        assertThrows(IllegalArgumentException.class, () -> TunnelProtocol.decodeOffer(new byte[16]));
    }

    @Test
    void nativeCodecAndAccessorTargetsExist() throws Exception {
        assertNotNull(net.minecraft.network.PacketDecoder.class.getDeclaredField("protocolInfo"));
        assertNotNull(CustomPacketPayload.class.getDeclaredMethod("codec", CustomPacketPayload.FallbackProvider.class, List.class));
        assertNotNull(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.class.getDeclaredField("GAMEPLAY_STREAM_CODEC"));
        assertNotNull(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.class.getDeclaredField("CONFIG_STREAM_CODEC"));
    }

    private static byte[] fragment(UUID session, long sequence, byte[] body) {
        return ByteBuffer.allocate(TunnelProtocol.HEADER_BYTES + body.length)
            .putLong(session.getMostSignificantBits()).putLong(session.getLeastSignificantBits())
            .putLong(sequence).put(body).array();
    }
}
