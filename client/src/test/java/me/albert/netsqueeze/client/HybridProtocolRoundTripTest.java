package me.albert.netsqueeze.client;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.CorruptedFrameException;
import me.albert.netsqueeze.protocol.TunnelProtocol;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HybridProtocolRoundTripTest {
    @Test
    void exactServerStreamPreservesDeflateDictionaryAroundIndependentZstdPackets() throws Exception {
        assertTrue(ZstdSupport.available());
        List<byte[]> actual = new ArrayList<>();
        ByteBuf wire = Unpooled.wrappedBuffer(Files.readAllBytes(path("hybrid-wire.bin")));
        try (FrameInflater decoder = new FrameInflater()) {
            while (wire.isReadable()) {
                int length = StreamDecoder.readVarInt(wire);
                assertTrue(length > 0 && length <= wire.readableBytes());
                decoder.decode(UnpooledByteBufAllocator.DEFAULT, wire.readSlice(length), true, true,
                    packet -> capture(actual, packet));
            }
        } finally { wire.release(); }
        assertPackets(records("hybrid-expected.bin"), actual);
    }

    @Test
    void exactServerTunnelReassemblesMultiFragmentZstdAndRetainsSmallPacketOrder() throws Exception {
        List<byte[]> carriers = records("hybrid-tunnel.bin");
        ByteBuffer first = ByteBuffer.wrap(carriers.getFirst());
        UUID session = new UUID(first.getLong(), first.getLong());
        List<byte[]> actual = new ArrayList<>();
        try (TunnelReassembler decoder = new TunnelReassembler(session, true, true)) {
            for (byte[] fragment : carriers) {
                assertTrue(fragment.length <= TunnelProtocol.FRAGMENT_BYTES + TunnelProtocol.HEADER_BYTES);
                decoder.accept(UnpooledByteBufAllocator.DEFAULT, fragment, packet -> capture(actual, packet));
            }
        }
        assertPackets(records("hybrid-expected.bin"), actual);
    }

    @Test
    void noZstdCapabilityRejectsZstdBeforeCallingNativeCode() {
        ByteBuf body = Unpooled.wrappedBuffer(new byte[]{1, 99});
        try (FrameInflater decoder = new FrameInflater()) {
            assertThrows(CorruptedFrameException.class,
                () -> decoder.decode(UnpooledByteBufAllocator.DEFAULT, body, true, false, packet -> fail()));
        } finally { body.release(); }
    }

    @Test
    void oversizedContentSizeIsRejectedBeforeNativeAllocation() {
        byte[] compressed = Zstd.compress(new byte[8 * 1024 * 1024 + 6], 1);
        assertThrows(CorruptedFrameException.class, () -> ZstdFrameDecoder.decode(compressed));
    }

    @Test
    void capabilityAndLegacyOfferAreStrictlySeparated() {
        int protocol = net.minecraft.SharedConstants.getProtocolVersion();
        UUID session = UUID.randomUUID();
        TunnelProtocol.Offer legacy = TunnelProtocol.decodeOffer(TunnelProtocol.encodeOffer(session, protocol));
        assertFalse(legacy.markedFrames());
        assertFalse(legacy.zstd());
        byte[] modern = TunnelProtocol.encodeOffer(session, protocol, true);
        assertTrue(TunnelProtocol.decodeOffer(modern).zstd());
        assertTrue(TunnelProtocol.decodeOffer(modern).markedFrames());
        modern[20] = 2;
        assertThrows(IllegalArgumentException.class, () -> TunnelProtocol.decodeOffer(modern));
        assertNotEquals(TunnelProtocol.ackPath(session, protocol),
            TunnelProtocol.ackPath(session, protocol, true, true));
    }

    private static void capture(List<byte[]> output, ByteBuf packet) {
        try {
            byte[] bytes = new byte[packet.readableBytes()];
            packet.readBytes(bytes);
            output.add(bytes);
        } finally { packet.release(); }
    }
    private static void assertPackets(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i));
    }
    private static Path path(String name) { return Path.of(System.getProperty("netsqueeze.fixtures"), name); }
    private static List<byte[]> records(String name) throws Exception {
        List<byte[]> result = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path(name)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) result.add(in.readNBytes(in.readInt()));
        }
        return result;
    }
}
