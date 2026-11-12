package me.albert.netsqueeze.stats

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/** 隧道内部字节，不是 Velocity 面向客户端的 TCP 流量；外层仍由 WireCounter 数后端出网。 */
internal object TunnelStats {
    val active = AtomicInteger()
    val raw = LongAdder()
    val payload = LongAdder()
    val frames = LongAdder()
    val zstdRaw = LongAdder()
    val zstdFrames = LongAdder()
    val failures = LongAdder()

    fun reset() = listOf(raw, payload, frames, zstdRaw, zstdFrames, failures).forEach { it.reset() }
}
