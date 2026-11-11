package me.albert.netsqueeze.stats

import org.bukkit.entity.Player
import java.util.UUID
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
internal class PlayerTally(@Volatile var name: String) {
    @Volatile
    var tunnel = false

    val raw = LongAdder()
    val wire = LongAdder()

    @Volatile
    var streaming = false
        private set

    /**
     * 每次进服调:这次和上次装没装 mod 不一样就清零重来。表按 UUID 存、下线暂时保留历史，
     * 但由 [TrafficStats] 的 TTL 和容量上限负责淘汰。
     */
    fun startSession(streaming: Boolean) {
        if (this.streaming == streaming) return
        reset()
        this.streaming = streaming
    }

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

    private val history = PlayerHistory()

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

    @Volatile
    var sampleTunnelPipeline: List<String> = emptyList()

    val players get() = history.entries

    fun tally(player: Player, session: Any): PlayerTally =
        history.open(player.uniqueId, player.name, session)

    fun endSession(uuid: UUID, session: Any) = history.retire(uuid, session)

    fun recordWire(tally: PlayerTally, bytes: Long) {
        tally.wire.add(bytes)
        wire.add(bytes)
        wireFlushes.increment()
        wireSegments.add(segmentsOf(bytes))
    }

    fun reset() {
        listOf(wire, wireFlushes, wireSegments).forEach { it.reset() }
        history.reset()
        since = System.currentTimeMillis()
    }
}
