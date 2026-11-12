package me.albert.netsqueeze.client;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.handler.codec.CorruptedFrameException;

/** Decode one bounded, content-sized ZSTD frame with a maximum 8 MiB history window. */
final class ZstdFrameDecoder {
    private static final int WINDOW_LOG = 23;
    private static final int MAX_CONTENT = 8 * 1024 * 1024 + 5;
    private static final int MAX_FRAME = 16 * 1024 * 1024;

    private ZstdFrameDecoder() { }

    static byte[] decode(byte[] compressed) {
        if (compressed.length == 0 || compressed.length > MAX_FRAME) {
            throw new CorruptedFrameException("NetSqueeze ZSTD 压缩帧长度无效");
        }
        final long frameSize;
        final long contentSize;
        try {
            frameSize = Zstd.findFrameCompressedSize(compressed);
            contentSize = Zstd.getFrameContentSize(compressed);
        } catch (RuntimeException e) {
            throw new CorruptedFrameException("NetSqueeze ZSTD 帧头损坏", e);
        }
        if (frameSize != compressed.length || Zstd.isError(contentSize) ||
                contentSize <= 0 || contentSize > MAX_CONTENT) {
            throw new CorruptedFrameException("NetSqueeze ZSTD 内容长度或帧边界无效");
        }
        ZstdDecompressCtx ctx = new ZstdDecompressCtx();
        try {
            ctx.setLongMax(WINDOW_LOG);
            byte[] output = ctx.decompress(compressed, (int) contentSize);
            if (output.length != contentSize) throw new CorruptedFrameException("NetSqueeze ZSTD 解压长度不匹配");
            return output;
        } catch (RuntimeException e) {
            throw new CorruptedFrameException("NetSqueeze ZSTD 解压失败", e);
        } finally { ctx.close(); }
    }
}
