package me.albert.netsqueeze.stats

import java.util.concurrent.atomic.LongAdder

/** Counts selections, not measured compression savings or VC egress. */
internal object ChunkCacheStats {
    val full = LongAdder()
    val hits = LongAdder()
    val patches = LongAdder()
    fun reset() { full.reset(); hits.reset(); patches.reset() }
}
