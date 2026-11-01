package me.albert.netsqueeze.shadow

import me.albert.netsqueeze.logger
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder

/** 估 TCP 段数用的 MSS,以太网 1500 减 IP/TCP 头 */
internal const val MSS = 1460

/** 每个 TCP 段的 IP + TCP 头(Windows 默认不开时间戳选项) */
internal const val TCP_IP_HEADER = 40

internal fun segmentsOf(bytes: Long): Long = (bytes + MSS - 1) / MSS

internal fun varIntSize(value: Long): Int = when {
    value < 1L shl 7 -> 1
    value < 1L shl 14 -> 2
    value < 1L shl 21 -> 3
    value < 1L shl 28 -> 4
    else -> 5
}

/** 一个玩家的累计量。两个处理器都在这条连接的 netty 线程上写,命令线程读 */
internal class PlayerTally(val name: String) {
    val raw = LongAdder()
    val wire = LongAdder()
    val shadow = LongAdder()

    fun reset() {
        raw.reset()
        wire.reset()
        shadow.reset()
    }
}

/**
 * 全服汇总。三个口径(都不含 TCP/IP 头,包头单独按段数估):
 * - raw:未压缩的包,每个包 = varint 长度 + 包 id + 包体,和 XAFK 的口径基本一致(多了长度前缀)
 * - wire:原版实际出网,prepender 之后的字节(已按阈值逐包压缩、含帧头)
 * - shadow:影子压缩,每次 flush 的全部包拼成一帧、整条连接共用一个 deflate 流,帧长 varint + 压缩输出
 */
internal object ShadowStats {

    val raw = LongAdder()
    val wire = LongAdder()
    val shadow = LongAdder()
    val packets = LongAdder()
    val wireFlushes = LongAdder()
    val shadowFrames = LongAdder()
    val wireSegments = LongAdder()
    val shadowSegments = LongAdder()
    val deflateNanos = LongAdder()
    val failures = LongAdder()

    @Volatile
    var since = System.currentTimeMillis()
        private set

    /** 最近一次注入后的管线,`/nsq pipeline` 核对处理器位置用 */
    @Volatile
    var samplePipeline: List<String> = emptyList()

    val players = ConcurrentHashMap<UUID, PlayerTally>()

    private val failureLogged = AtomicBoolean()

    fun tally(player: Player): PlayerTally = players.computeIfAbsent(player.uniqueId) { PlayerTally(player.name) }

    fun recordShadow(tally: PlayerTally, rawBytes: Long, frameBytes: Long, packetCount: Int, nanos: Long) {
        tally.raw.add(rawBytes)
        tally.shadow.add(frameBytes)
        raw.add(rawBytes)
        shadow.add(frameBytes)
        packets.add(packetCount.toLong())
        shadowFrames.increment()
        shadowSegments.add(segmentsOf(frameBytes))
        deflateNanos.add(nanos)
    }

    fun recordWire(tally: PlayerTally, bytes: Long) {
        tally.wire.add(bytes)
        wire.add(bytes)
        wireFlushes.increment()
        wireSegments.add(segmentsOf(bytes))
    }

    /** 影子这边出错只停掉这一条连接的影子压缩,真实连接照常;堆栈全服只打第一条 */
    fun recordFailure(player: String, e: Throwable) {
        failures.increment()
        if (!failureLogged.compareAndSet(false, true)) return
        logger.severe("影子压缩出错(玩家 $player),该连接停止统计,之后的同类错误只计数不打印")
        e.printStackTrace()
    }

    fun reset() {
        listOf(raw, wire, shadow, packets, wireFlushes, shadowFrames, wireSegments, shadowSegments, deflateNanos, failures)
            .forEach { it.reset() }
        // 在线的保留(处理器手里攥着这份 tally),下线的趁清零丢掉
        players.keys.removeIf { Bukkit.getPlayer(it) == null }
        players.values.forEach { it.reset() }
        since = System.currentTimeMillis()
    }
}
