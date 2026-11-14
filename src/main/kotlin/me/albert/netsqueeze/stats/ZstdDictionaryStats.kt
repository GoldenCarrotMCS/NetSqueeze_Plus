package me.albert.netsqueeze.stats

import java.util.concurrent.atomic.LongAdder

/** Selected compressed bodies vs a same-packet independent ZSTD trial, NOT VC egress or total savings. */
internal object ZstdDictionaryStats {
    val frames = LongAdder()
    val saved = LongAdder()
    fun reset() { frames.reset(); saved.reset() }
}
