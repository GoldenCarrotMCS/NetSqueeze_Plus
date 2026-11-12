package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.network.CompressionDecoder;

/** 两种算法共用有界帧解压器；output 接管每个 ByteBuf 的所有权。 */
final class FrameInflater implements AutoCloseable {
    private static final int MAX_INFLATED = 64 * 1024 * 1024;
    private static final int CHUNK = 8 * 1024;
    private static final int DEFLATE_MARKER = 0;
    private static final int ZSTD_MARKER = 1;
    private final Inflater inflater = new Inflater(true);
    private boolean closed;

    void decode(ByteBufAllocator alloc, ByteBuf frame, boolean markedFrames, boolean allowZstd,
                Consumer<ByteBuf> output) throws DataFormatException {
        if (closed) throw new IllegalStateException("inflater closed");
        if (!frame.isReadable()) throw new CorruptedFrameException("NetSqueeze 空压缩帧");
        if (!markedFrames) {
            decodeDeflate(alloc, frame, output);
            return;
        }
        int marker = frame.readUnsignedByte();
        if (marker == ZSTD_MARKER) {
            if (!allowZstd) throw new CorruptedFrameException("未协商 ZSTD，但收到 ZSTD 帧");
            decodeZstd(frame, output);
            return;
        }
        if (marker != DEFLATE_MARKER) throw new CorruptedFrameException("NetSqueeze 未知压缩算法");
        decodeDeflate(alloc, frame, output);
    }

    private void decodeZstd(ByteBuf frame, Consumer<ByteBuf> output) {
        byte[] compressed = new byte[frame.readableBytes()];
        frame.readBytes(compressed);
        final byte[] raw;
        try {
            raw = ZstdFrameDecoder.decode(compressed);
        } catch (RuntimeException e) {
            throw new CorruptedFrameException("NetSqueeze ZSTD 解压失败", e);
        }
        ByteBuf out = Unpooled.wrappedBuffer(raw);
        try {
            processPackets(out, output);
        } finally {
            out.release();
        }
    }

    private void decodeDeflate(ByteBufAllocator alloc, ByteBuf frame, Consumer<ByteBuf> output)
            throws DataFormatException {
        final int end = frame.writerIndex();
        if (frame.readableBytes() < 4 || frame.getUnsignedByte(end - 4) != 0 ||
                frame.getUnsignedByte(end - 3) != 0 || frame.getUnsignedByte(end - 2) != 255 ||
                frame.getUnsignedByte(end - 1) != 255) {
            throw new CorruptedFrameException("NetSqueeze Deflate 帧缺少 SYNC_FLUSH 边界");
        }
        this.inflater.setInput(frame.nioBuffer());
        final ByteBuf out = alloc.directBuffer(CHUNK, MAX_INFLATED + 1);
        try {
            while (true) {
                out.ensureWritable(Math.min(CHUNK, out.maxCapacity() - out.writerIndex()));
                final int space = out.writableBytes();
                final int produced = this.inflater.inflate(out.internalNioBuffer(out.writerIndex(), space));
                out.writerIndex(out.writerIndex() + produced);
                if (out.readableBytes() > MAX_INFLATED) throw new CorruptedFrameException("NetSqueeze 帧解压后过大");
                if (this.inflater.finished() || this.inflater.needsDictionary()) {
                    throw new CorruptedFrameException("NetSqueeze Deflate 流异常结束");
                }
                if (produced < space && this.inflater.needsInput()) break;
                if (produced == 0) throw new CorruptedFrameException("NetSqueeze Deflate 流卡住");
            }
            if (this.inflater.getRemaining() != 0) {
                throw new CorruptedFrameException("NetSqueeze Deflate 帧包含尾部数据");
            }
            processPackets(out, output);
        } finally {
            out.release();
        }
    }

    private void processPackets(ByteBuf out, Consumer<ByteBuf> output) {
        int packets = 0;
        while (out.isReadable()) {
            if (closed) return;
            if (++packets > 4096) throw new CorruptedFrameException("NetSqueeze 单帧包数量过大");
            final int length = StreamDecoder.readVarInt(out);
            if (length <= 0 || length > out.readableBytes()) {
                throw new CorruptedFrameException("NetSqueeze 帧里有半个包");
            }
            if (length > CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH) {
                throw new CorruptedFrameException("NetSqueeze 包过大");
            }
            output.accept(out.readRetainedSlice(length));
        }
    }

    @Override public void close() {
        if (!closed) { closed = true; inflater.end(); }
    }
}
