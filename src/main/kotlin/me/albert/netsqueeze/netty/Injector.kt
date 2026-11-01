package me.albert.netsqueeze.netty

import io.netty.channel.Channel
import me.albert.corelib.utils.registerEvents
import me.albert.netsqueeze.instance
import me.albert.netsqueeze.shadow.FlushProbe
import me.albert.netsqueeze.shadow.ShadowCompressor
import me.albert.netsqueeze.shadow.WireCounter
import me.albert.netsqueeze.stats.ShadowStats
import net.minecraft.network.HandlerNames
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * 往每个玩家的 netty 管线里挂影子压缩和原版计数两个处理器。出站方向(从尾到头)的顺序是:
 * **flush 探针**(最尾)→ … → encoder(Via/PacketEvents 挂在它附近)→ **影子压缩** → compress → prepender → **原版计数** → encrypt。
 *
 * 处理器随连接关闭由 netty 自动拆掉,退服不用管;插件卸载时同步摘干净,支持 PlugManX 热重载
 * (重载后统计归零,压缩上下文重新开始)。
 */
internal object Injector : Listener {

    private const val SHADOW = "netsqueeze_shadow"
    private const val WIRE = "netsqueeze_wire"
    private const val PROBE = "netsqueeze_probe"

    /** 卸载时最多等多久摘完,拍脑袋定的 */
    private const val SHUTDOWN_WAIT_MS = 3000L

    /**
     * 卸载开始后不再挂新的。Folia 上进服事件在各区域线程并发触发,onDisable 跑完到 Bukkit 注销监听器之间
     * 还可能有人进服,它排的注入任务若排在摘除任务后面,就会把旧类加载器的处理器挂回去
     */
    @Volatile
    private var stopped = false

    fun init() {
        instance.registerEvents(this)
        Bukkit.getOnlinePlayers().forEach(::inject)
    }

    /**
     * 必须等全部摘完再返回:热重载紧接着关掉本插件的类加载器,摘掉之前旧处理器还在 netty 线程上处理包,
     * 碰到没加载过的类就 NoClassDefFoundError,netty 把它当连接异常处理会踢人。
     * 摘除时 handlerRemoved 顺带 end 掉 deflate 流,不漏原生内存。
     */
    fun shutdown() {
        stopped = true
        val futures = Bukkit.getOnlinePlayers().map { player ->
            val channel = channelOf(player)
            channel.eventLoop().submit {
                val pipeline = channel.pipeline()
                if (pipeline.get(SHADOW) != null) pipeline.remove(SHADOW)
                if (pipeline.get(WIRE) != null) pipeline.remove(WIRE)
                if (pipeline.get(PROBE) != null) pipeline.remove(PROBE)
            }
        }
        // 各连接分散在几条 netty 线程上并行摘,正常几毫秒;总等待封顶,netty 线程卡死也不至于把卸载拖住
        val deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS
        futures.forEach { it.awaitUninterruptibly((deadline - System.currentTimeMillis()).coerceAtLeast(0)) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) = inject(e.player)

    private fun inject(player: Player) {
        val channel = channelOf(player)
        val tally = ShadowStats.tally(player)
        // 管线只能在自己的 netty 线程上改,和正在发的包排队,不会插到半个包中间
        channel.eventLoop().execute {
            val pipeline = channel.pipeline()
            // 在 netty 线程上查 stopped:置位后才跑到这里的一律不挂;置位前已经挂上的,摘除任务排在它后面会摘掉
            if (stopped || !channel.isActive || pipeline.get(SHADOW) != null) return@execute
            // 阈值设成 -1 时没有 compress,影子就直接挂在 prepender 尾侧
            val anchor = if (pipeline.get(HandlerNames.COMPRESS) != null) HandlerNames.COMPRESS else HandlerNames.PREPENDER
            pipeline.addAfter(anchor, SHADOW, ShadowCompressor(tally))
            pipeline.addBefore(HandlerNames.PREPENDER, WIRE, WireCounter(tally))
            // 挂在 TailContext 前面,channel.write 进来的包最先经过它
            pipeline.addLast(PROBE, FlushProbe())
            ShadowStats.samplePipeline = pipeline.names()
        }
    }

    private fun channelOf(player: Player): Channel = (player as CraftPlayer).handle.connection.connection.channel
}
