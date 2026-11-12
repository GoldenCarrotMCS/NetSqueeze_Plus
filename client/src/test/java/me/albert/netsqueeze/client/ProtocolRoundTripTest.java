package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolRoundTripTest {
    private static final byte[] ENABLE = {1, 0x42};

    /** 只替换 Minecraft 包解码，不替换生产 StreamDecoder/EnableDetector 或压缩格式。 */
    private static EmbeddedChannel channel(boolean decompress) { return channel(decompress, false, false); }

    private static EmbeddedChannel channel(boolean decompress, boolean zstd, boolean legacy) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(StreamDecoder.NAME, new StreamDecoder());
        ch.pipeline().addLast(HandlerNames.SPLITTER, new ChannelInboundHandlerAdapter());
        if (decompress) ch.pipeline().addLast(HandlerNames.DECOMPRESS, new ChannelInboundHandlerAdapter());
        ch.pipeline().addLast("test_packet_decoder", new ChannelInboundHandlerAdapter() {
            private boolean first = true;
            @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (first) {
                    first = false;
                    ByteBuf bytes = (ByteBuf) msg;
                    try {
                        assertEquals(1, bytes.readUnsignedByte());
                        assertEquals(0x42, bytes.readUnsignedByte());
                    } finally { bytes.release(); }
                    ctx.fireChannelRead(new ClientboundCustomPayloadPacket(new DiscardedPayload(legacy ? NetSqueezeClient.LEGACY_ENABLE : zstd ? NetSqueezeClient.ENABLE_ZSTD : NetSqueezeClient.ENABLE)));
                } else ctx.fireChannelRead(msg);
            }
        });
        ch.pipeline().addLast(EnableDetector.NAME, new EnableDetector());
        ch.pipeline().addLast(HandlerNames.PACKET_HANDLER, new ChannelInboundHandlerAdapter());
        return ch;
    }

    @Test
    void decodesActualServerCompressorFixturesWithRandomTcpFragmentation() throws Exception {
        Path dir = Path.of(System.getProperty("netsqueeze.fixtures"));
        List<byte[]> expected = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(dir.resolve("expected.bin")))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) expected.add(in.readNBytes(in.readInt()));
        }
        for (int level : new int[]{0, 6, 9}) for (boolean decompress : new boolean[]{false, true}) {
            EmbeddedChannel ch = channel(decompress);
            try {
                byte[] wire = concat(ENABLE, Files.readAllBytes(dir.resolve("level-" + level + ".bin")));
                Random random = new Random(0x5eed);
                for (int offset = 0; offset < wire.length;) {
                    int n = Math.min(wire.length - offset, 1 + random.nextInt(5000));
                    ch.writeInbound(Unpooled.wrappedBuffer(wire, offset, n));
                    offset += n;
                }
                for (byte[] packet : expected) assertArrayEquals(packet, readPacket(ch));
                assertNull(ch.readInbound());
            } finally { ch.finishAndReleaseAll(); }
        }
    }

    @Test
    void zstdHandshakeAndMixedFramesDecodeThroughRealDetectorWithFragmentation() throws Exception {
        List<byte[]> expected = new ArrayList<>();
        Path dir = Path.of(System.getProperty("netsqueeze.fixtures"));
        try (DataInputStream in = new DataInputStream(Files.newInputStream(dir.resolve("hybrid-expected.bin")))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) expected.add(in.readNBytes(in.readInt()));
        }
        EmbeddedChannel ch = channel(false, true, false);
        try {
            byte[] wire = concat(ENABLE, Files.readAllBytes(dir.resolve("hybrid-wire.bin")));
            Random random = new Random(411);
            for (int offset = 0; offset < wire.length;) {
                int length = Math.min(wire.length - offset, 1 + random.nextInt(5000));
                ch.writeInbound(Unpooled.wrappedBuffer(wire, offset, length));
                offset += length;
            }
            for (byte[] packet : expected) assertArrayEquals(packet, readPacket(ch));
            assertNull(ch.readInbound());
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test
    void legacyEnableStillDecodesUnmarkedDeflate() {
        byte[] packet = new byte[513];
        new Random(33).nextBytes(packet);
        byte[] marked = compressed(concat(varInt(packet.length), packet));
        ByteBuf framed = Unpooled.wrappedBuffer(marked);
        final byte[] legacyFrame;
        try {
            int length = StreamDecoder.readVarInt(framed);
            assertEquals(0, framed.readUnsignedByte());
            byte[] body = new byte[length - 1];
            framed.readBytes(body);
            legacyFrame = concat(varInt(body.length), body);
        } finally { framed.release(); }
        EmbeddedChannel ch = channel(false, false, true);
        try {
            ch.writeInbound(Unpooled.wrappedBuffer(concat(ENABLE, legacyFrame)));
            assertArrayEquals(packet, readPacket(ch));
            assertNull(ch.readInbound());
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test
    void enableAndStreamCanArriveInOneReadOrOneByteAtATime() {
        byte[] packet = new byte[513];
        new Random(73).nextBytes(packet);
        byte[] wire = concat(ENABLE, compressed(concat(varInt(packet.length), packet)));
        for (boolean fragmented : new boolean[]{false, true}) {
            EmbeddedChannel ch = channel(false);
            try {
                if (fragmented) for (byte b : wire) ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{b}));
                else ch.writeInbound(Unpooled.wrappedBuffer(wire));
                assertArrayEquals(packet, readPacket(ch));
            } finally { ch.finishAndReleaseAll(); }
        }
    }

    @Test
    void rejectsOversizeDeclaredLengthBeforeAnyBodyArrives() {
        EmbeddedChannel ch = channel(false);
        try {
            ch.writeInbound(Unpooled.wrappedBuffer(ENABLE));
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> ch.writeInbound(Unpooled.wrappedBuffer(varInt(16 * 1024 * 1024 + 1))));
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test
    void rejectsZeroNegativeAndOverflowLengthsWithoutLeakingInput() {
        for (byte[] bytes : new byte[][]{{0}, {-1, -1, -1, -1, 15}, {-1, -1, -1, -1, 127}}) {
            EmbeddedChannel ch = channel(false);
            ByteBuf input = Unpooled.wrappedBuffer(bytes);
            try {
                assertThrows(io.netty.handler.codec.DecoderException.class, () -> ch.writeInbound(input));
                assertEquals(0, input.refCnt());
            } finally { ch.finishAndReleaseAll(); }
        }
    }

    @Test
    void rejectsHalfPacketAndMissingSyncFlush() {
        for (byte[] bad : new byte[][]{compressed(new byte[]{10, 1}), concat(varInt(4), new byte[]{1, 2, 3, 4})}) {
            EmbeddedChannel ch = channel(false);
            try {
                ch.writeInbound(Unpooled.wrappedBuffer(ENABLE));
                assertThrows(io.netty.handler.codec.DecoderException.class, () -> ch.writeInbound(Unpooled.wrappedBuffer(bad)));
            } finally { ch.finishAndReleaseAll(); }
        }
    }

    @Test
    void boundsDecompressionBeforeAllocatingBeyondProtocolLimit() {
        EmbeddedChannel ch = channel(false);
        try {
            ch.writeInbound(Unpooled.wrappedBuffer(ENABLE));
            byte[] bomb = compressed(new byte[64 * 1024 * 1024 + 1]);
            assertThrows(io.netty.handler.codec.DecoderException.class,
                    () -> ch.writeInbound(Unpooled.wrappedBuffer(bomb)));
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test
    void giveUpReturnsPartialNextFrameAndRemainingInputUnmodified() {
        EmbeddedChannel ch = new EmbeddedChannel();
        StreamDecoder decoder = new StreamDecoder();
        ch.pipeline().addLast(StreamDecoder.NAME, decoder);
        ch.pipeline().addLast(HandlerNames.SPLITTER, new ChannelInboundHandlerAdapter() {
            private boolean first = true;
            @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (first) { first = false; decoder.giveUp(); }
                ctx.fireChannelRead(msg);
            }
        });
        // 大于 decoder 的复制批次；撤掉后本次输入中尚未复制的部分也必须交还。
        byte[] rest = new byte[20_000];
        new Random(33).nextBytes(rest);
        byte[] wire = concat(ENABLE, rest);
        try {
            ch.writeInbound(Unpooled.wrappedBuffer(wire));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (ByteBuf b; (b = ch.readInbound()) != null;) {
                try { byte[] data = new byte[b.readableBytes()]; b.readBytes(data); out.writeBytes(data); }
                finally { b.release(); }
            }
            assertArrayEquals(wire, out.toByteArray());
            assertNull(ch.pipeline().get(StreamDecoder.NAME));
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test
    void encryptionRepositionsDecoderAfterDecrypt() {
        EmbeddedChannel ch = channel(false);
        try {
            StreamDecoder old = (StreamDecoder) ch.pipeline().get(StreamDecoder.NAME);
            ch.pipeline().addBefore(HandlerNames.SPLITTER, HandlerNames.DECRYPT, new ChannelInboundHandlerAdapter());
            NetSqueezeClient.moveAfterDecrypt(ch.pipeline());
            assertNotSame(old, ch.pipeline().get(StreamDecoder.NAME));
            List<String> names = ch.pipeline().names();
            assertTrue(names.indexOf(HandlerNames.DECRYPT) < names.indexOf(StreamDecoder.NAME));
            assertTrue(names.indexOf(StreamDecoder.NAME) < names.indexOf(HandlerNames.SPLITTER));
        } finally { ch.finishAndReleaseAll(); }
    }

    private static byte[] readPacket(EmbeddedChannel ch) {
        ByteBuf packet = assertInstanceOf(ByteBuf.class, ch.readInbound());
        try {
            byte[] bytes = new byte[packet.readableBytes()];
            packet.readBytes(bytes);
            return bytes;
        } finally { packet.release(); }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(a); out.writeBytes(b);
        return out.toByteArray();
    }

    private static byte[] varInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int part = value & 127;
            value >>>= 7;
            out.write(value == 0 ? part : part | 128);
        } while (value != 0);
        return out.toByteArray();
    }

    private static byte[] compressed(byte[] raw) {
        Deflater deflater = new Deflater(6, true);
        try {
            deflater.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            while (true) {
                int produced = deflater.deflate(chunk, 0, chunk.length, Deflater.SYNC_FLUSH);
                out.write(chunk, 0, produced);
                if (produced < chunk.length && deflater.needsInput()) break;
            }
            byte[] body = concat(new byte[]{0}, out.toByteArray());
            return concat(varInt(body.length), body);
        } finally { deflater.end(); }
    }
}
