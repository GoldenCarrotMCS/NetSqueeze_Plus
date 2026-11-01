package me.albert.netsqueeze.stream

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import net.minecraft.network.VarInt
import java.util.zip.Deflater

/**
 * 按帧的流式 deflate:整条连接共用一个 deflate 流,[feed] 逐包喂,[finish] 在 flush 时 SYNC_FLUSH 收成一帧。
 * 影子压缩和真压缩共用,影子量完大小就丢。
 *
 * 帧格式(客户端 mod 照此解):varint 帧长 + deflate 输出;解压后是一串"varint 包长 + 包字节(包 id + 包体)"。
 * SYNC_FLUSH 保证每帧解压出来都是整包,不会有半个包留到下一帧。
 * 不加锁,只在一条连接的 netty 线程上用。
 */
internal class FrameDeflater(private val alloc: ByteBufAllocator) {

    private companion object {
        /** 和 Paper 默认(Velocity natives,compression-level -1)同一档 */
        const val LEVEL = 6

        /** 帧开头先空出 varint 帧长的最大字节数,压完再回填,省一次拷贝 */
        const val HEADER = VarInt.MAX_VARINT_SIZE

        const val INITIAL = 8 * 1024

        /** 每次 deflate 前至少留出这么多输出空间 */
        const val CHUNK = 8 * 1024
    }

    /** nowrap:帧里不需要 zlib 头和校验和 */
    private val deflater = Deflater(LEVEL, true)
    private val lengthPrefix = ByteArray(VarInt.MAX_VARINT_SIZE)
    private var frame: ByteBuf? = null

    /** 本帧已喂的原始字节(含包长前缀),[finish] 后清零 */
    var pendingRaw = 0L
        private set

    var pendingPackets = 0
        private set

    fun feed(buf: ByteBuf) {
        val out = frame ?: alloc.directBuffer(INITIAL).also {
            it.writerIndex(HEADER)
            frame = it
        }
        val length = buf.readableBytes()
        val prefix = writeVarInt(length)
        deflater.setInput(lengthPrefix, 0, prefix)
        deflate(out, Deflater.NO_FLUSH)
        // nioBuffer 是视图,不动 buf 的读指针;deflate 把它吃完才返回,之后调用方才能释放 buf
        deflater.setInput(buf.nioBuffer())
        deflate(out, Deflater.NO_FLUSH)
        pendingRaw += prefix + length
        pendingPackets++
    }

    /** 收尾成一帧交给调用方(由调用方释放);本帧一个包都没有时返回 null */
    fun finish(): ByteBuf? {
        val out = frame ?: return null
        frame = null
        deflate(out, Deflater.SYNC_FLUSH)
        val end = out.writerIndex()
        val body = end - HEADER
        val start = HEADER - VarInt.getByteSize(body)
        out.writerIndex(start)
        VarInt.write(out, body)
        out.writerIndex(end)
        out.readerIndex(start)
        pendingRaw = 0
        pendingPackets = 0
        return out
    }

    /** deflate 流占着原生内存,连接关闭或出错时必须调 */
    fun close() {
        deflater.end()
        frame?.release()
        frame = null
    }

    /**
     * NO_FLUSH 一直压到输入吃完(输出可能是 0,攒在 deflate 内部);SYNC_FLUSH 压到输出没把空间填满为止,
     * 填满说明还有没吐完的,扩容接着要(Deflater 文档的约定)
     */
    private fun deflate(out: ByteBuf, mode: Int) {
        while (true) {
            out.ensureWritable(CHUNK)
            val space = out.writableBytes()
            val produced = deflater.deflate(out.internalNioBuffer(out.writerIndex(), space), mode)
            out.writerIndex(out.writerIndex() + produced)
            if (mode == Deflater.NO_FLUSH && deflater.needsInput()) return
            if (mode != Deflater.NO_FLUSH && produced < space) return
        }
    }

    private fun writeVarInt(value: Int): Int {
        var v = value
        var i = 0
        while (v and 0x7F.inv() != 0) {
            lengthPrefix[i++] = (v and 0x7F or 0x80).toByte()
            v = v ushr 7
        }
        lengthPrefix[i++] = v.toByte()
        return i
    }
}
