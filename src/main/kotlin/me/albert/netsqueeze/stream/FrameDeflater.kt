package me.albert.netsqueeze.stream

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import me.albert.netsqueeze.platform.VarInts as VarInt
import java.util.zip.Deflater

/** 连接共享 Deflate 字典；旧帧保持原字节，新协商帧可增加算法标记。 */
internal class FrameDeflater(
    private val alloc: ByteBufAllocator,
    level: Int = 6,
    private val markedFrames: Boolean = true
) {
    private companion object {
        const val HEADER = VarInt.MAX_VARINT_SIZE
        const val INITIAL = 8 * 1024
        const val CHUNK = 8 * 1024
        const val DEFLATE_MARKER = 0
    }

    private val deflater = Deflater(level, true)
    private val lengthPrefix = ByteArray(VarInt.MAX_VARINT_SIZE)
    private var frame: ByteBuf? = null
    private var closed = false
    private val markerBytes get() = if (markedFrames) 1 else 0

    var pendingRaw = 0L
        private set
    var pendingPackets = 0
        private set

    fun feed(buf: ByteBuf) {
        check(!closed) { "deflater closed" }
        val out = frame ?: alloc.directBuffer(INITIAL).also {
            it.writerIndex(HEADER + markerBytes)
            if (markedFrames) it.setByte(HEADER, DEFLATE_MARKER)
            frame = it
        }
        val length = buf.readableBytes()
        val prefix = writeVarInt(length)
        deflater.setInput(lengthPrefix, 0, prefix)
        deflate(out, Deflater.NO_FLUSH)
        deflater.setInput(buf.nioBuffer())
        deflate(out, Deflater.NO_FLUSH)
        pendingRaw += prefix + length
        pendingPackets++
    }

    fun finish(): ByteBuf? {
        val out = frame ?: return null
        deflate(out, Deflater.SYNC_FLUSH)
        val end = out.writerIndex()
        val bodyLength = end - HEADER
        val start = HEADER - VarInt.getByteSize(bodyLength)
        out.writerIndex(start)
        VarInt.write(out, bodyLength)
        out.writerIndex(end)
        out.readerIndex(start)
        frame = null
        pendingRaw = 0
        pendingPackets = 0
        return out
    }

    fun close() {
        if (closed) return
        closed = true
        deflater.end()
        frame?.release()
        frame = null
    }

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
