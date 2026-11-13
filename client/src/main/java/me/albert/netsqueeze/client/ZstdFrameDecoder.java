package me.albert.netsqueeze.client;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.handler.codec.CorruptedFrameException;
import me.albert.netsqueeze.protocol.ZstdFrameHeader;

/** Decode one bounded, content-sized ZSTD frame with a maximum 8 MiB history window. */
final class ZstdFrameDecoder {
    private ZstdFrameDecoder() { }

    static byte[] decode(byte[] compressed) {
        final ZstdFrameHeader.Header header;
        try { header = ZstdFrameHeader.validate(compressed); }
        catch (IllegalArgumentException e) { throw new CorruptedFrameException("NetSqueeze ZSTD 帧头或窗口无效", e); }
        final long frameSize;
        final long contentSize;
        try {
            frameSize = Zstd.findFrameCompressedSize(compressed);
            contentSize = Zstd.getFrameContentSize(compressed);
        } catch (RuntimeException e) {
            throw new CorruptedFrameException("NetSqueeze ZSTD 帧头损坏", e);
        }
        if (frameSize != compressed.length || Zstd.isError(contentSize) ||
                contentSize != header.contentSize()) {
            throw new CorruptedFrameException("NetSqueeze ZSTD 内容长度或帧边界无效");
        }
        ZstdDecompressCtx ctx = new ZstdDecompressCtx();
        try {
            // Do not hard-link setLongMax: older zstd-jni classes embedded by other
            // Fabric mods lack it. The pure-Java preflight enforces the same window cap.
            byte[] output = ctx.decompress(compressed, (int) contentSize);
            if (output.length != contentSize) throw new CorruptedFrameException("NetSqueeze ZSTD 解压长度不匹配");
            return output;
        } catch (RuntimeException e) {
            throw new CorruptedFrameException("NetSqueeze ZSTD 解压失败", e);
        } finally { ctx.close(); }
    }
}
