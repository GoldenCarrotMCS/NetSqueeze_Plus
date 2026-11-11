package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.network.CompressionDecoder;

/** 两种传输共用有界帧解压器；output 接管每个 ByteBuf 的所有权。 */
final class FrameInflater implements AutoCloseable {
    private static final int MAX_INFLATED = 64 * 1024 * 1024;
    private static final int CHUNK = 8 * 1024;
    private final Inflater inflater = new Inflater(true);
    private boolean closed;

    void decode(ByteBufAllocator alloc, ByteBuf frame, Consumer<ByteBuf> output) throws DataFormatException {
        if (closed) throw new IllegalStateException("inflater closed");
        // raw deflate 的每个 v1 帧必须完整结束于 SYNC_FLUSH，而非等待下一帧补完。
        final int end = frame.writerIndex();
        if (frame.readableBytes() < 4 || frame.getUnsignedByte(end - 4) != 0 ||
                frame.getUnsignedByte(end - 3) != 0 || frame.getUnsignedByte(end - 2) != 255 ||
                frame.getUnsignedByte(end - 1) != 255) {
            throw new CorruptedFrameException("NetSqueeze 帧缺少 SYNC_FLUSH 边界");
        }
        this.inflater.setInput(frame.nioBuffer());
        final ByteBuf out = alloc.directBuffer(CHUNK, MAX_INFLATED + 1);
        try {
            // 输出没填满且输入已吃完,这一帧就全出来了(SYNC_FLUSH 保证);填满了说明可能还有,扩容接着要
            while (true) {
                out.ensureWritable(Math.min(CHUNK, out.maxCapacity() - out.writerIndex()));
                final int space = out.writableBytes();
                final int produced = this.inflater.inflate(out.internalNioBuffer(out.writerIndex(), space));
                out.writerIndex(out.writerIndex() + produced);
                if (out.readableBytes() > MAX_INFLATED) throw new CorruptedFrameException("NetSqueeze 帧解压后过大");
                if (this.inflater.finished() || this.inflater.needsDictionary()) throw new CorruptedFrameException("NetSqueeze 压缩流异常结束");
                if (produced < space && this.inflater.needsInput()) break;
                if (produced == 0) throw new CorruptedFrameException("NetSqueeze 压缩流卡住");
            }
            if (this.inflater.getRemaining() != 0) {
                throw new CorruptedFrameException("NetSqueeze 压缩帧包含尾部数据");
            }

            int packets = 0;
            while (out.isReadable()) {
                if (closed) return;
                if (++packets > 4096) throw new CorruptedFrameException("NetSqueeze 单帧包数量过大");
                final int length = StreamDecoder.readVarInt(out);
                if (length <= 0 || length > out.readableBytes()) throw new CorruptedFrameException("NetSqueeze 帧里有半个包");
                if (length > CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH) throw new CorruptedFrameException("NetSqueeze 包过大: " + length);
                output.accept(out.readRetainedSlice(length));
            }
        } finally {
            out.release();
        }
    }

    @Override public void close() {
        if (!closed) { closed = true; inflater.end(); }
    }
}
