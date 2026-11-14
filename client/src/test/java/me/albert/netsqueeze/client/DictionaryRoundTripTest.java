package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.CorruptedFrameException;
import me.albert.netsqueeze.protocol.SnapshotCodec;
import me.albert.netsqueeze.protocol.ZstdDictionary;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static me.albert.netsqueeze.client.ProtocolFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Also executed against zstd-jni 1.5.7-17 in the separate compatibility JVM. */
class DictionaryRoundTripTest {
    @Test void productionProbeSupportsOldAndNewDictionaryApis() {
        assertTrue(ZstdDictionarySupport.available());
    }
    @Test void actualServerFramesPreserveBothDictionariesAndPacketOrder() throws Exception {
        List<byte[]> actual = new ArrayList<>();
        int dictionaryFrames = 0;
        try (FrameInflater decoder = new FrameInflater()) {
            decoder.enableDictionary();
            for (byte[] bytes : records("dictionary-frames.bin")) {
                ByteBuf wire = Unpooled.wrappedBuffer(bytes);
                try {
                    int length = StreamDecoder.readVarInt(wire);
                    if (wire.getUnsignedByte(wire.readerIndex()) == ZstdDictionary.MARKER) dictionaryFrames++;
                    decoder.decode(UnpooledByteBufAllocator.DEFAULT, wire.readSlice(length), true, true, packet -> capture(actual, packet));
                } finally { wire.release(); }
            }
        }
        assertTrue(dictionaryFrames >= 2);
        assertPackets(records("dictionary-expected.bin"), actual);
    }
    @Test void actualServerTunnelHandlesPlainAndCacheEnvelopeModes() throws Exception {
        for (boolean cache : new boolean[]{false, true}) {
            List<byte[]> carriers = records("dictionary-tunnel-" + cache + ".bin");
            ByteBuffer header = ByteBuffer.wrap(carriers.getFirst());
            UUID session = new UUID(header.getLong(), header.getLong());
            List<byte[]> actual = new ArrayList<>();
            try (SnapshotCodec snapshots = new SnapshotCodec();
                 TunnelReassembler decoder = new TunnelReassembler(session, true, true, cache, true)) {
                for (byte[] data : carriers) decoder.accept(UnpooledByteBufAllocator.DEFAULT, data, packet -> {
                    try {
                        byte[] bytes = new byte[packet.readableBytes()]; packet.readBytes(bytes);
                        actual.add(cache ? snapshots.decode(bytes).packet() : bytes);
                    } finally { packet.release(); }
                });
            }
            assertPackets(records("dictionary-expected.bin"), actual);
        }
    }
    @Test void rejectsMissingNegotiationWrongBaseTruncationAndNewSessionReferences() throws Exception {
        withFirstDictionaryFrame(false, (decoder, body) ->
            assertThrows(CorruptedFrameException.class,
                () -> decoder.decode(UnpooledByteBufAllocator.DEFAULT, body, true, true, ByteBuf::release)));
        byte[] reference = withFirstDictionaryFrame(true, (decoder, body) -> {
            body.setByte(body.readerIndex() + 1, body.getByte(body.readerIndex() + 1) ^ 1);
            assertThrows(CorruptedFrameException.class,
                () -> decoder.decode(UnpooledByteBufAllocator.DEFAULT, body, true, true, ByteBuf::release));
        });
        try (FrameInflater decoder = new FrameInflater()) {
            decoder.enableDictionary();
            ByteBuf body = Unpooled.wrappedBuffer(reference);
            try {
                assertThrows(CorruptedFrameException.class,
                    () -> decoder.decode(UnpooledByteBufAllocator.DEFAULT, body, true, true, ByteBuf::release));
            } finally { body.release(); }
            ByteBuf truncated = Unpooled.wrappedBuffer(new byte[]{2, 1});
            try {
                assertThrows(CorruptedFrameException.class,
                    () -> decoder.decode(UnpooledByteBufAllocator.DEFAULT, truncated, true, true, ByteBuf::release));
            } finally { truncated.release(); }
        }
    }
    @Test void dictionaryDecoderChecksChecksumAndDictionaryLimitBeforeJNI() {
        byte[] headerOnly = {0x28, (byte)0xb5, 0x2f, (byte)0xfd, 0x20, 1};
        var error = assertThrows(CorruptedFrameException.class,
            () -> ZstdFrameDecoder.decode(headerOnly, new byte[8]));
        assertTrue(error.getMessage().contains("checksum"));
        assertThrows(CorruptedFrameException.class,
            () -> ZstdFrameDecoder.decode(headerOnly, new byte[ZstdDictionary.BYTES + 1]));
    }
    /** Decodes real preceding frames, owns their buffers, and exposes the first dictionary body. */
    private static byte[] withFirstDictionaryFrame(boolean negotiate,
            java.util.function.BiConsumer<FrameInflater, ByteBuf> scenario) throws Exception {
        try (FrameInflater decoder = new FrameInflater()) {
            if (negotiate) decoder.enableDictionary();
            for (byte[] bytes : records("dictionary-frames.bin")) {
                ByteBuf wire = Unpooled.wrappedBuffer(bytes);
                try {
                    int length = StreamDecoder.readVarInt(wire);
                    ByteBuf body = wire.readSlice(length);
                    if (body.getUnsignedByte(body.readerIndex()) == ZstdDictionary.MARKER) {
                        byte[] original = new byte[body.readableBytes()];
                        body.getBytes(body.readerIndex(), original);
                        scenario.accept(decoder, body);
                        return original;
                    }
                    decoder.decode(UnpooledByteBufAllocator.DEFAULT, body, true, true, ByteBuf::release);
                } finally { wire.release(); }
            }
        }
        throw new AssertionError("Server fixture did not exercise dictionary frames");
    }
    @Test void dictionaryDisableDoesNotDisableIndependentZstd() {
        String previous = System.getProperty("netsqueeze.zstd.dictionary.disable");
        try {
            System.setProperty("netsqueeze.zstd.dictionary.disable", "true");
            assertFalse(ZstdDictionarySupport.available());
            assertTrue(ZstdSupport.available());
        } finally {
            if (previous == null) System.clearProperty("netsqueeze.zstd.dictionary.disable");
            else System.setProperty("netsqueeze.zstd.dictionary.disable", previous);
        }
    }
    @Test void closeDuringDeliveryReleasesDictionaryWithoutDoubleFree() throws Exception {
        withFirstDictionaryFrame(true, (decoder, body) -> {
            try {
                decoder.decode(UnpooledByteBufAllocator.DEFAULT, body, true, true, packet -> {
                    packet.release(); decoder.close();
                });
            } catch (java.util.zip.DataFormatException e) { throw new AssertionError(e); }
        });
    }
}
