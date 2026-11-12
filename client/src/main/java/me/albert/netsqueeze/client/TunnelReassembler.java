package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.CorruptedFrameException;
import me.albert.netsqueeze.protocol.TunnelProtocol;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;

/** 每个后端会话独立字典；仅接收连续序号，旧后端会话的延迟载荷不会污染新字典。 */
final class TunnelReassembler implements AutoCloseable {
    final UUID session;
    private final FrameInflater inflater = new FrameInflater();
    final boolean markedFrames;
    final boolean zstdEnabled;
    private ByteBuf pending;
    private long sequence;
    private boolean closed;

    TunnelReassembler(UUID session) { this(session, true, false); }

    TunnelReassembler(UUID session, boolean markedFrames, boolean zstdEnabled) {
        this.session = session;
        this.markedFrames = markedFrames;
        this.zstdEnabled = zstdEnabled;
    }

    void accept(ByteBufAllocator alloc, byte[] data, Consumer<ByteBuf> output) throws DataFormatException {
        if (closed) throw new IllegalStateException("tunnel closed");
        if (data.length <= TunnelProtocol.HEADER_BYTES ||
                data.length > TunnelProtocol.HEADER_BYTES + TunnelProtocol.FRAGMENT_BYTES) {
            throw new CorruptedFrameException("NetSqueeze 隧道分片长度错误");
        }
        ByteBuffer header = ByteBuffer.wrap(data);
        UUID sender = new UUID(header.getLong(), header.getLong());
        if (!sender.equals(session)) return;
        if (header.getLong() != sequence) throw new CorruptedFrameException("NetSqueeze 隧道分片乱序/缺失");
        sequence = Math.incrementExact(sequence);
        if (pending == null) pending = alloc.buffer(8192, TunnelProtocol.MAX_FRAME + 5);
        int length = data.length - TunnelProtocol.HEADER_BYTES;
        if (length > pending.maxCapacity() - pending.writerIndex()) {
            throw new CorruptedFrameException("NetSqueeze 隧道重组超过上限");
        }
        pending.writeBytes(data, TunnelProtocol.HEADER_BYTES, length);
        int start = pending.readerIndex();
        int frameLength = StreamDecoder.readVarInt(pending);
        if (frameLength < 0) { pending.readerIndex(start); return; }
        if (frameLength == 0 || frameLength > TunnelProtocol.MAX_FRAME) {
            throw new CorruptedFrameException("NetSqueeze 隧道帧长度无效");
        }
        if (pending.readableBytes() < frameLength) { pending.readerIndex(start); return; }
        if (pending.readableBytes() != frameLength) throw new CorruptedFrameException("NetSqueeze 隧道帧多余数据");
        // 下游处理包可能同步关闭连接；先转移所有权，close() 就不会再次释放当前帧。
        ByteBuf complete = pending;
        pending = null;
        try { inflater.decode(alloc, complete.readSlice(frameLength), markedFrames, zstdEnabled, output); }
        finally { complete.release(); }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        inflater.close();
        if (pending != null) { pending.release(); pending = null; }
    }
}
