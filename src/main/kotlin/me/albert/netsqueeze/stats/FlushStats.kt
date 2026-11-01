package me.albert.netsqueeze.stats

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * flush 来源诊断(查清后连同 FlushProbe 一起删):线上每人每 tick 十几次 flush,
 * 按每次 flush 带几个包分档,并按包类型统计小 flush 里都是什么包,看是谁在一包一 flush。
 *
 * 类型键:包对象用它的 Class;PacketEvents 这类直接写 ByteBuf 的用 Int 包 id(服务端协议号,Via 转换之前)。
 */
internal object FlushStats {

    /** 带的包不超过这个数算小 flush */
    const val SMALL = 2

    /** 各档包数上界,最后一档不封顶 */
    private val BUCKET_MAX = intArrayOf(1, 2, 5, 20)

    val BUCKET_LABELS = listOf("1", "2", "3~5", "6~20", "21+")

    val buckets = List(BUCKET_LABELS.size) { LongAdder() }
    val emptyFlushes = LongAdder()
    val packets = LongAdder()

    val byType = ConcurrentHashMap<Any, LongAdder>()
    val smallByType = ConcurrentHashMap<Any, LongAdder>()

    fun recordPacket(type: Any) {
        packets.increment()
        adder(byType, type).increment()
    }

    /** [small] 里前 min(count, SMALL) 个是这次 flush 的包类型 */
    fun recordFlush(count: Int, small: Array<Any?>) {
        if (count == 0) {
            emptyFlushes.increment()
            return
        }
        val bucket = BUCKET_MAX.indexOfFirst { count <= it }.takeIf { it >= 0 } ?: BUCKET_MAX.size
        buckets[bucket].increment()
        if (count > SMALL) return
        for (i in 0..<count) adder(smallByType, small[i] ?: continue).increment()
    }

    /** 嵌套类保留外层名(`ClientboundMoveEntityPacket$Pos`),只写 simpleName 分不出是谁 */
    fun label(type: Any): String {
        if (type is Class<*>) return type.name.substringAfterLast('.')
        return "ByteBuf 0x%02X".format(type)
    }

    fun reset() {
        buckets.forEach { it.reset() }
        emptyFlushes.reset()
        packets.reset()
        byType.clear()
        smallByType.clear()
    }

    private fun adder(map: ConcurrentHashMap<Any, LongAdder>, type: Any): LongAdder =
        map[type] ?: map.computeIfAbsent(type) { LongAdder() }
}
