package me.albert.netsqueeze.platform

import io.netty.buffer.ByteBuf

/** Wire primitive independent of NMS linkage. */
internal object VarInts {
    const val MAX_VARINT_SIZE = 5
    fun getByteSize(value: Int): Int {
        require(value >= 0)
        var remaining = value
        var bytes = 1
        while ((remaining ushr 7) != 0) { remaining = remaining ushr 7; bytes++ }
        return bytes
    }
    fun write(out: ByteBuf, value: Int) {
        require(value >= 0)
        var remaining = value
        while (remaining and 0x7f.inv() != 0) {
            out.writeByte(remaining and 0x7f or 0x80)
            remaining = remaining ushr 7
        }
        out.writeByte(remaining)
    }
}
