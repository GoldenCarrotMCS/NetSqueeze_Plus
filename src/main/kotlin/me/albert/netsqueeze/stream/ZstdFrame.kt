package me.albert.netsqueeze.stream

import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import me.albert.netsqueeze.platform.VarInts

/** 每个大包独立 ZSTD 帧，不接触任何 Deflate 字典。窗口固定最多 8 MiB。 */
internal object ZstdFrame {
    const val MARKER: Byte = 1
    const val MAX_INPUT = 8 * 1024 * 1024
    const val MAX_COMPRESSED = 16 * 1024 * 1024
    private const val WINDOW_LOG = 23
    private val nativeAvailable: Boolean by lazy {
        try {
            val ctx = ZstdCompressCtx()
            try {
                ctx.setLevel(1).setWindowLog(WINDOW_LOG).setContentSize(true)
                ctx.compress(byteArrayOf(0)).isNotEmpty()
            } finally { ctx.close() }
        } catch (_: LinkageError) { false
        } catch (_: RuntimeException) { false }
    }

    fun available(): Boolean = !java.lang.Boolean.getBoolean("netsqueeze.zstd.disable") && nativeAvailable

    /** null 表示不比 raw 包帧更小；尚未推进共享字典，调用者可安全回退 Deflate。 */
    fun encode(alloc: ByteBufAllocator, packet: ByteBuf, level: Int): ByteBuf? {
        val packetLength = packet.readableBytes()
        require(packetLength in 1..MAX_INPUT)
        val raw = ByteArray(VarInts.getByteSize(packetLength) + packetLength)
        val rawBuf = Unpooled.wrappedBuffer(raw).clear()
        try {
            VarInts.write(rawBuf, packetLength)
            packet.getBytes(packet.readerIndex(), raw, rawBuf.writerIndex(), packetLength)
        } finally { rawBuf.release() }

        val compressed = try {
            val ctx = ZstdCompressCtx()
            try {
                ctx.setLevel(level).setWindowLog(WINDOW_LOG).setContentSize(true).setChecksum(true)
                ctx.compress(raw)
            } finally { ctx.close() }
        } catch (_: LinkageError) { return null }
          catch (_: RuntimeException) { return null }
        if (compressed.size + 1 >= raw.size) return null
        require(compressed.size <= MAX_COMPRESSED) { "ZSTD 压缩帧超过上限" }
        val bodyLength = 1 + compressed.size
        val out = alloc.directBuffer(VarInts.MAX_VARINT_SIZE + bodyLength)
        try {
            VarInts.write(out, bodyLength)
            out.writeByte(MARKER.toInt())
            out.writeBytes(compressed)
            return out
        } catch (e: Throwable) {
            out.release()
            throw e
        }
    }

    /** Header content-size 校验与 8 MiB 窗口上限，避免尺寸声明和窗口 bomb。 */
    fun decode(compressed: ByteArray): ByteArray {
        require(compressed.isNotEmpty() && compressed.size <= MAX_COMPRESSED) { "ZSTD 压缩帧长度无效" }
        val frameBytes = com.github.luben.zstd.Zstd.findFrameCompressedSize(compressed)
        require(frameBytes == compressed.size.toLong()) { "ZSTD 帧包含尾随数据" }
        val contentBytes = com.github.luben.zstd.Zstd.getFrameContentSize(compressed)
        require(!com.github.luben.zstd.Zstd.isError(contentBytes) && contentBytes in 1..(MAX_INPUT + VarInts.MAX_VARINT_SIZE).toLong()) {
            "ZSTD 解压长度无效: $contentBytes"
        }
        val ctx = ZstdDecompressCtx()
        try {
            ctx.setLongMax(WINDOW_LOG)
            val output = ctx.decompress(compressed, contentBytes.toInt())
            require(output.size.toLong() == contentBytes) { "ZSTD 解压长度不匹配" }
            return output
        } finally { ctx.close() }
    }
}
