package me.albert.netsqueeze.stats

import me.albert.netsqueeze.logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * 装了 mod、真走流式压缩的连接的全服汇总。口径和 [ShadowStats] 的影子一栏相同,只是这里是真发出去的字节
 * (帧长 varint + 压缩输出,不含 TCP/IP 头,包头按段数估)。时间起点共用 [ShadowStats.since]。
 */
internal object StreamStats {

    val raw = LongAdder()
    val wire = LongAdder()
    val packets = LongAdder()
    val frames = LongAdder()
    val segments = LongAdder()
    val deflateNanos = LongAdder()
    val failures = LongAdder()

    /** 当前挂着真压缩的连接数,不随 reset 清零 */
    val active = AtomicInteger()

    private val failureLogged = AtomicBoolean()

    /** [tally] 在进服前(配置阶段)还没绑上,这段只记全服 */
    fun record(tally: PlayerTally?, rawBytes: Long, frameBytes: Long, packetCount: Int, nanos: Long) {
        tally?.raw?.add(rawBytes)
        tally?.wire?.add(frameBytes)
        raw.add(rawBytes)
        wire.add(frameBytes)
        packets.add(packetCount.toLong())
        frames.increment()
        segments.add(segmentsOf(frameBytes))
        deflateNanos.add(nanos)
    }

    /** 真压缩出错只能断开这条连接(客户端已切格式,退不回原版);堆栈全服只打第一条 */
    fun recordFailure(player: String, e: Throwable) {
        failures.increment()
        if (!failureLogged.compareAndSet(false, true)) return
        logger.severe("流式压缩出错(玩家 $player),已断开该连接,之后的同类错误只计数不打印")
        e.printStackTrace()
    }

    fun reset() {
        listOf(raw, wire, packets, frames, segments, deflateNanos, failures).forEach { it.reset() }
    }
}
