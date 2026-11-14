package me.albert.netsqueeze.stats

import me.albert.netsqueeze.instance
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.Locale

/** `/nsq` 展示端,纯读 [TrafficStats] 和 [StreamStats] */
internal object StatsCommand : CommandExecutor, TabCompleter {

    private const val TOP = 15

    private val SUBS = listOf("players", "reset", "pipeline")

    fun init() {
        val command = instance.getCommand("netsqueeze") ?: return
        command.setExecutor(this)
        command.tabCompleter = this
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase()

        if (sub == "reset") {
            TrafficStats.reset()
            StreamStats.reset()
            TunnelStats.reset()
            ChunkCacheStats.reset()
            ZstdDictionaryStats.reset()
            sender.sendMessage("§a统计已清零§7，压缩上下文不动，跑几分钟后 §f/nsq §7看结果")
            return true
        }

        if (sub == "players") {
            sendPlayers(sender)
            return true
        }

        if (sub == "pipeline") {
            sendPipeline(sender, "没装 mod", TrafficStats.samplePipeline)
            sendPipeline(sender, "直连 mod", TrafficStats.sampleStreamPipeline)
            sendPipeline(sender, "代理隧道 mod", TrafficStats.sampleTunnelPipeline)
            return true
        }

        sendSummary(sender)
        return true
    }

    /** 人均按当前在线人数摊,只是个参考;要准就在人数稳定时 reset 后看 */
    private fun sendSummary(sender: CommandSender) {
        val seconds = elapsedSeconds()
        val online = Bukkit.getOnlinePlayers().size
        val streaming = StreamStats.active.get()
        val tunnel = TunnelStats.active.get()
        val vanilla = (online - streaming).coerceAtLeast(0)

        sender.sendMessage("§6--------- GoldenCarrot服务器 / NetSqueeze ---------")
        sender.sendMessage("§7时长 §f${duration(seconds)} §7| 在线 §f$online §7人，直连流 §f$streaming §7人，代理隧道 §f$tunnel §7人")

        val t = TrafficStats
        val wire = t.wire.sum()
        val wireHeaders = t.wireSegments.sum() * TCP_IP_HEADER
        sender.sendMessage(
            "§7标准传输(含隧道外层)：后端出网 §f${size(wire)} §7(§f${mbps(wire, seconds)}§7)，包头约 §f${mbps(wireHeaders, seconds)}" +
                "§7，人均 §f${kbps(wire + wireHeaders, seconds, vanilla)}"
        )

        sender.sendMessage("§7区块缓存：完整 §f${ChunkCacheStats.full.sum()}§7，命中 §f${ChunkCacheStats.hits.sum()}§7，补丁 §f${ChunkCacheStats.patches.sum()}§7；选择次数，不是 VC 出口节省。")
        sender.sendMessage("§7ZSTD 字典帧 §f${ZstdDictionaryStats.frames.sum()}§7，较同包独立 ZSTD 压缩体累计少 §f${size(ZstdDictionaryStats.saved.sum())}§7（含字典摘要开销，非 VC 出口实测）。")
        val s = StreamStats
        val raw = s.raw.sum()
        if (tunnel > 0 || TunnelStats.raw.sum() > 0) {
            sender.sendMessage("§b代理隧道：§7白名单原始 §f${size(TunnelStats.raw.sum())} §7→ 载荷 §f${size(TunnelStats.payload.sum())} §7(§f${percent(TunnelStats.payload.sum(), TunnelStats.raw.sum())}§7)，帧 §f${TunnelStats.frames.sum()}§7，错误 §f${TunnelStats.failures.sum()}")
            sender.sendMessage("§8隧道载荷不是 VC→玩家实际出网；外层大小受代理压缩配置影响。")
            if (TunnelStats.zstdFrames.sum() > 0) {
                sender.sendMessage("§7隧道 ZSTD 大包 §f${TunnelStats.zstdFrames.sum()}§7 帧，原始 §f${size(TunnelStats.zstdRaw.sum())}§7")
            }
        }
        if (streaming == 0 && raw == 0L) return
        val streamWire = s.wire.sum()
        val streamHeaders = s.segments.sum() * TCP_IP_HEADER
        sender.sendMessage(
            "§b直连 mod：§7原始 §f${size(raw)} §7→ 出网 §f${size(streamWire)} §7(§f${mbps(streamWire, seconds)}§7，原始的 §f${percent(streamWire, raw)}§7)" +
                "，包头约 §f${mbps(streamHeaders, seconds)}§7，人均 §f${kbps(streamWire + streamHeaders, seconds, streaming)}"
        )
        val nanosPerSecond = (s.deflateNanos.sum() + s.zstdNanos.sum()) / seconds
        sender.sendMessage(
            "§7真压缩 §f${count(s.frames.sum() / seconds)} §7帧/s，耗时 §f${"%.1f".format(Locale.ROOT, nanosPerSecond / 1e6)} ms/s" +
                " §7(约 §f${"%.1f".format(Locale.ROOT, nanosPerSecond / 1e7)}%§7 个核心，摊在 netty 线程上)"
        )
        if (s.zstdFrames.sum() > 0) {
            sender.sendMessage("§7直连 ZSTD 大包 §f${s.zstdFrames.sum()}§7 帧，原始 §f${size(s.zstdRaw.sum())} §7→ 载荷 §f${size(s.zstdWire.sum())}")
        }
        val failures = s.failures.sum()
        if (failures > 0) sender.sendMessage("§c真压缩出错 $failures 次，出错的连接已断开，第一条堆栈见后台")
    }

    private fun sendPlayers(sender: CommandSender) {
        val rows = TrafficStats.players.values.sortedByDescending { it.wire.sum() }.take(TOP)
        sender.sendMessage("§6--------- GoldenCarrot服务器 / NetSqueeze 玩家排行(按后端统计出网) ---------")
        if (rows.isEmpty()) {
            sender.sendMessage("§7还没有数据")
            return
        }
        rows.forEachIndexed { i, t ->
            val wire = t.wire.sum()
            if (t.tunnel) {
                sender.sendMessage("§7${i + 1}. §b[mod/tunnel] §f${t.name} §7后端出网 §f${size(wire)} §7| 隧道白名单原始 §f${size(t.raw.sum())}")
                return@forEachIndexed
            }
            if (t.streaming) {
                val raw = t.raw.sum()
                sender.sendMessage(
                    "§7${i + 1}. §b[mod] §f${t.name} §7原始 §f${size(raw)} §7→ 出网 §f${size(wire)} §7(原始的 §f${percent(wire, raw)}§7)"
                )
                return@forEachIndexed
            }
            sender.sendMessage("§7${i + 1}. §f${t.name} §7出网 §f${size(wire)}")
        }
    }

    private fun sendPipeline(sender: CommandSender, label: String, names: List<String>) {
        if (names.isEmpty()) {
            sender.sendMessage("§7$label：还没有玩家进服")
            return
        }
        sender.sendMessage("§7$label 的玩家最近一次进服时的管线(头 → 尾，出站从尾往头走):")
        sender.sendMessage("§f" + names.joinToString(" §7→ §f"))
    }

    private fun elapsedSeconds() = ((System.currentTimeMillis() - TrafficStats.since) / 1000.0).coerceAtLeast(1.0)

    private fun size(bytes: Long): String {
        if (bytes >= 1L shl 30) return "%.2f GB".format(Locale.ROOT, bytes / (1L shl 30).toDouble())
        if (bytes >= 1L shl 20) return "%.2f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())
        if (bytes >= 1L shl 10) return "%.1f KB".format(Locale.ROOT, bytes / (1L shl 10).toDouble())
        return "$bytes B"
    }

    /** 带宽口径和机房限速一致:十进制兆比特 */
    private fun mbps(bytes: Long, seconds: Double) = "%.1f Mbps".format(Locale.ROOT, bytes * 8 / seconds / 1e6)

    private fun kbps(bytes: Long, seconds: Double, players: Int) =
        if (players == 0) "-" else "%.0f kbps".format(Locale.ROOT, bytes * 8 / seconds / 1e3 / players)

    private fun percent(part: Long, whole: Long) = if (whole == 0L) "-" else "%.1f%%".format(Locale.ROOT, part * 100.0 / whole)

    private fun count(perSecond: Double): String {
        if (perSecond >= 1e6) return "%.1fM".format(Locale.ROOT, perSecond / 1e6)
        if (perSecond >= 1e3) return "%.1fk".format(Locale.ROOT, perSecond / 1e3)
        return "%.0f".format(Locale.ROOT, perSecond)
    }

    private fun duration(seconds: Double): String {
        val total = seconds.toLong()
        val minutes = total / 60
        return if (minutes == 0L) "${total}秒" else "${minutes}分${total % 60}秒"
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        return SUBS.filter { it.startsWith(args[0].lowercase()) }
    }
}
