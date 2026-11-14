package me.albert.netsqueeze.stream

import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import me.albert.netsqueeze.platform.VarInts
import me.albert.netsqueeze.protocol.ZstdDictionary
import me.albert.netsqueeze.protocol.ZstdFrameHeader
import me.albert.netsqueeze.stats.ZstdDictionaryStats

/** Independent ZSTD frames; the optional raw dictionary belongs to one negotiated connection. */
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
    private val dictionaryNativeAvailable: Boolean by lazy {
        try {
            val raw = ByteArray(24 * 1024).also { java.util.Random(739016).nextBytes(it) }
            ZstdDictionary().use { dictionary ->
                dictionary.update(raw)
                val compressed = ZstdCompressCtx().use { ctx ->
                    configure(ctx, 1).loadDict(dictionary.content()).compress(raw)
                }
                ZstdDecompressCtx().use { ctx ->
                    ctx.loadDict(dictionary.content())
                    ctx.decompress(compressed, raw.size).contentEquals(raw)
                }
            }
        } catch (_: LinkageError) { false
        } catch (_: RuntimeException) { false }
    }
    fun available(): Boolean = !java.lang.Boolean.getBoolean("netsqueeze.zstd.disable") && nativeAvailable
    fun dictionaryAvailable(): Boolean = available() && dictionaryNativeAvailable

    /** null never mutates either dictionary. Only actual selected ZSTD frames advance history. */
    fun encode(alloc: ByteBufAllocator, packet: ByteBuf, level: Int, dictionary: ZstdDictionary? = null): ByteBuf? {
        val packetLength = packet.readableBytes()
        require(packetLength in 1..MAX_INPUT)
        val raw = ByteArray(VarInts.getByteSize(packetLength) + packetLength)
        val rawBuf = Unpooled.wrappedBuffer(raw).clear()
        try {
            VarInts.write(rawBuf, packetLength)
            packet.getBytes(packet.readerIndex(), raw, rawBuf.writerIndex(), packetLength)
        } finally { rawBuf.release() }
        val ctx = try { ZstdCompressCtx() }
            catch (_: LinkageError) { return null }
            catch (_: RuntimeException) { return null }
        try {
            val independent = try { configure(ctx, level).compress(raw) }
                catch (_: LinkageError) { return null }
                catch (_: RuntimeException) { return null }
            // A comparison owns ONE native context, released before returning this frame.
            // Independent compression runs first, so no previous dictionary can leak into it.
            val selectedDictionary = if (dictionary?.ready() == true && dictionaryCanWin(independent.size)) {
                val trial = try { ctx.loadDict(dictionary.content()).compress(raw) }
                    catch (_: LinkageError) { null }
                    catch (_: RuntimeException) { null }
                trial?.takeIf { it.size + ZstdDictionary.HASH_BYTES + MIN_DICTIONARY_SAVING <= independent.size }
            } else null
            val compressed = selectedDictionary ?: independent
            val useDictionary = selectedDictionary != null
            val extra = if (useDictionary) ZstdDictionary.HASH_BYTES else 0
            if (compressed.size + 1 + extra >= raw.size) return null
            require(compressed.size <= MAX_COMPRESSED)
            val bodyLength = 1 + extra + compressed.size
            val out = alloc.directBuffer(VarInts.MAX_VARINT_SIZE + bodyLength)
            try {
                VarInts.write(out, bodyLength)
                out.writeByte(if (useDictionary) ZstdDictionary.MARKER else MARKER.toInt())
                if (useDictionary) out.writeBytes(checkNotNull(dictionary).digest())
                out.writeBytes(compressed)
                dictionary?.update(raw)
                if (useDictionary) {
                    ZstdDictionaryStats.frames.increment()
                    ZstdDictionaryStats.saved.add((independent.size - compressed.size - extra).toLong())
                }
                return out
            } catch (e: Throwable) { out.release(); throw e }
        } finally { ctx.close() }
    }
    private const val MIN_DICTIONARY_SAVING = 64

    /** Necessary bound, not a heuristic: even a zero-byte trial cannot win below this. */
    internal fun dictionaryCanWin(independentBytes: Int): Boolean =
        independentBytes >= ZstdDictionary.HASH_BYTES + MIN_DICTIONARY_SAVING

    private fun configure(ctx: ZstdCompressCtx, level: Int): ZstdCompressCtx =
        ctx.setLevel(level).setWindowLog(WINDOW_LOG).setContentSize(true).setChecksum(true)
    /** Test helper for ordinary independent frames. Production client uses the same Java header check. */
    fun decode(compressed: ByteArray): ByteArray {
        val header = ZstdFrameHeader.validate(compressed)
        require(com.github.luben.zstd.Zstd.findFrameCompressedSize(compressed) == compressed.size.toLong())
        require(com.github.luben.zstd.Zstd.getFrameContentSize(compressed) == header.contentSize().toLong())
        val ctx = ZstdDecompressCtx()
        try {
            val output = ctx.decompress(compressed, header.contentSize())
            require(output.size == header.contentSize())
            return output
        } finally { ctx.close() }
    }
}
