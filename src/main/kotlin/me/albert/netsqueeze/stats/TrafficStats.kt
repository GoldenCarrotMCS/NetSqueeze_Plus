package me.albert.netsqueeze.stats

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/** 估 TCP 段数用的 MSS,以太网 1500 减 IP/TCP 头 */
internal const val MSS = 1460

/** 每个 TCP 段的 IP + TCP 头(Windows 默认不开时间戳选项) */
internal const val TCP_IP_HEADER = 40

internal fun segmentsOf(bytes: Long): Long = (bytes + MSS - 1) / MSS

/**
 * 一个玩家的累计量。处理器都在这条连接的 netty 线程上写,命令线程读。
 * wire 是实际发出去的字节;raw(未压缩的包)只有装了 mod 的([streaming])才量,没装的不量
 */
internal class PlayerTally(val name: String) {
    val raw = LongAdder()
    val wire = LongAdder()

    @Volatile
    var streaming = false

    fun reset() {
        raw.reset()
        wire.reset()
    }
}

/**
 * 没装 mod 的玩家走原版格式的出网汇总(prepender 之后的字节,已按阈值逐包压缩、含帧头;不含 TCP/IP 头,包头按段数估),
 * 外加全服共用的玩家表、统计起点和管线样本。装了 mod 的见 [StreamStats]。
 */
internal object TrafficStats {

    val wire = LongAdder()
    val wireFlushes = LongAdder()
    val wireSegments = LongAdder()

    @Volatile
    var since = System.currentTimeMillis()
        private set

    /** 最近一个没装 mod 的玩家进服时的管线,`/nsq pipeline` 核对处理器位置用 */
    @Volatile
    var samplePipeline: List<String> = emptyList()

    /** 最近一个装 mod 的玩家进服时的管线,核对 Via/PacketEvents/Grim 的处理器是否都在真压缩的尾侧 */
    @Volatile
    var sampleStreamPipeline: List<String> = emptyList()

    val players = ConcurrentHashMap<UUID, PlayerTally>()

    fun tally(player: Player): PlayerTally = players.computeIfAbsent(player.uniqueId) { PlayerTally(player.name) }

    fun recordWire(tally: PlayerTally, bytes: Long) {
        tally.wire.add(bytes)
        wire.add(bytes)
        wireFlushes.increment()
        wireSegments.add(segmentsOf(bytes))
    }

    fun reset() {
        listOf(wire, wireFlushes, wireSegments).forEach { it.reset() }
        // 在线的保留(处理器手里攥着这份 tally),下线的趁清零丢掉
        players.keys.removeIf { Bukkit.getPlayer(it) == null }
        players.values.forEach { it.reset() }
        since = System.currentTimeMillis()
    }
}
