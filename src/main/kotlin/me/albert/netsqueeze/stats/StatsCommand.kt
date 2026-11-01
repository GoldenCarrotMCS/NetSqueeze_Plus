package me.albert.netsqueeze.stats

import me.albert.netsqueeze.instance
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/** `/nsq` 展示端,纯读 [ShadowStats] */
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
            ShadowStats.reset()
            StreamStats.reset()
            sender.sendMessage("§a统计已清零§7，压缩上下文不动，跑几分钟后 §f/nsq §7看结果")
            return true
        }

        if (sub == "players") {
            sendPlayers(sender)
            return true
        }

        if (sub == "pipeline") {
            val names = ShadowStats.samplePipeline
            if (names.isEmpty()) {
                sender.sendMessage("§c还没有注入过任何连接")
                return true
            }
            sender.sendMessage("§7最近一次注入后的管线(头 → 尾，出站从尾往头走):")
            sender.sendMessage("§f" + names.joinToString(" §7→ §f"))
            return true
        }

        sendSummary(sender)
        return true
    }

    private fun sendSummary(sender: CommandSender) {
        val s = ShadowStats
        val seconds = elapsedSeconds()
        val raw = s.raw.sum()
        val wire = s.wire.sum()
        val shadow = s.shadow.sum()
        val packets = s.packets.sum()
        val wireSegments = s.wireSegments.sum()
        val shadowSegments = s.shadowSegments.sum()
        val wireTotal = wire + wireSegments * TCP_IP_HEADER
        val shadowTotal = shadow + shadowSegments * TCP_IP_HEADER

        sender.sendMessage("§6--------- NetSqueeze 影子压缩 ---------")
        sender.sendMessage(
            "§7时长 §f${duration(seconds)} §7| 在线 §f${Bukkit.getOnlinePlayers().size} §7人(装 mod §f${StreamStats.active.get()}§7) | " +
                "flush §f${count(s.wireFlushes.sum() / seconds)}§7/s"
        )
        sender.sendMessage("§7以下几行是没装 mod 的玩家:")
        sender.sendMessage(
            "§7原始包 §f${size(raw)} §7(§f${count(packets / seconds)} §7包/s，均 §f${if (packets == 0L) 0 else raw / packets} B§7)"
        )
        sender.sendMessage("§7原版出网 §f${size(wire)} §7(§f${mbps(wire, seconds)}§7，原始的 §f${percent(wire, raw)}§7)")
        sender.sendMessage("§7影子压缩 §f${size(shadow)} §7(§f${mbps(shadow, seconds)}§7，原始的 §f${percent(shadow, raw)}§7)")
        sender.sendMessage(
            "§7TCP/IP 包头估算 原版 §f${count(wireSegments / seconds)} §7段/s ≈ §f${mbps(wireSegments * TCP_IP_HEADER, seconds)}" +
                "§7，影子 §f${count(shadowSegments / seconds)} §7段/s ≈ §f${mbps(shadowSegments * TCP_IP_HEADER, seconds)}"
        )
        sender.sendMessage(
            "§a全员换压缩后下行少 §f${percent(wireTotal - shadowTotal, wireTotal)}§a(含包头)，约 §f${mbps(wireTotal - shadowTotal, seconds)}"
        )
        val nanosPerSecond = s.deflateNanos.sum() / seconds
        sender.sendMessage(
            "§7压缩耗时 §f${"%.1f".format(nanosPerSecond / 1e6)} ms/s §7(约 §f${"%.1f".format(nanosPerSecond / 1e7)}%§7 个核心，摊在 netty 线程上)"
        )
        val failures = s.failures.sum()
        if (failures > 0) sender.sendMessage("§c影子出错 $failures 次，出错的连接已停止统计，第一条堆栈见后台")
        sendStream(sender, seconds)
    }

    /** 装了 mod、真走流式压缩的那部分;一个都没有就不显示 */
    private fun sendStream(sender: CommandSender, seconds: Double) {
        val s = StreamStats
        val raw = s.raw.sum()
        if (s.active.get() == 0 && raw == 0L) return
        val wire = s.wire.sum()
        val segments = s.segments.sum()
        val nanosPerSecond = s.deflateNanos.sum() / seconds
        sender.sendMessage("§b装 mod 的玩家(真压缩):")
        sender.sendMessage(
            "§7原始包 §f${size(raw)} §7→ 实际出网 §f${size(wire)} §7(§f${mbps(wire, seconds)}§7，原始的 §f${percent(wire, raw)}§7)" +
                "，包头约 §f${mbps(segments * TCP_IP_HEADER, seconds)}"
        )
        sender.sendMessage(
            "§7帧 §f${count(s.frames.sum() / seconds)}§7/s，压缩耗时 §f${"%.1f".format(nanosPerSecond / 1e6)} ms/s"
        )
        val failures = s.failures.sum()
        if (failures > 0) sender.sendMessage("§c真压缩出错 $failures 次，出错的连接已断开，第一条堆栈见后台")
    }

    private fun sendPlayers(sender: CommandSender) {
        val rows = ShadowStats.players.values.sortedByDescending { it.wire.sum() }.take(TOP)
        sender.sendMessage("§6--------- NetSqueeze 玩家排行(按实际出网) ---------")
        if (rows.isEmpty()) {
            sender.sendMessage("§7还没有数据")
            return
        }
        rows.forEachIndexed { i, t ->
            val wire = t.wire.sum()
            if (t.streaming) {
                val raw = t.raw.sum()
                sender.sendMessage(
                    "§7${i + 1}. §b[mod] §f${t.name} §7原始 §f${size(raw)} §7→ 实际 §f${size(wire)} §7(原始的 §f${percent(wire, raw)}§7)"
                )
                return@forEachIndexed
            }
            val shadow = t.shadow.sum()
            sender.sendMessage(
                "§7${i + 1}. §f${t.name} §7原版 §f${size(wire)} §7→ 影子 §f${size(shadow)} §7(少 §f${percent(wire - shadow, wire)}§7)"
            )
        }
    }

    private fun elapsedSeconds() = ((System.currentTimeMillis() - ShadowStats.since) / 1000.0).coerceAtLeast(1.0)

    private fun size(bytes: Long): String {
        if (bytes >= 1L shl 30) return "%.2f GB".format(bytes / (1L shl 30).toDouble())
        if (bytes >= 1L shl 20) return "%.2f MB".format(bytes / (1L shl 20).toDouble())
        if (bytes >= 1L shl 10) return "%.1f KB".format(bytes / (1L shl 10).toDouble())
        return "$bytes B"
    }

    /** 带宽口径和机房限速一致:十进制兆比特 */
    private fun mbps(bytes: Long, seconds: Double) = "%.1f Mbps".format(bytes * 8 / seconds / 1e6)

    private fun percent(part: Long, whole: Long) = if (whole == 0L) "-" else "%.1f%%".format(part * 100.0 / whole)

    private fun count(perSecond: Double): String {
        if (perSecond >= 1e6) return "%.1fM".format(perSecond / 1e6)
        if (perSecond >= 1e3) return "%.1fk".format(perSecond / 1e3)
        return "%.0f".format(perSecond)
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
