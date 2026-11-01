package me.albert.netsqueeze.shadow

import io.netty.channel.Channel
import me.albert.corelib.utils.registerEvents
import me.albert.netsqueeze.instance
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
 * encoder(Via/PacketEvents 挂在它附近)→ **影子压缩** → compress → prepender → **原版计数** → encrypt。
 *
 * 处理器随连接关闭由 netty 自动拆掉,退服不用管;插件卸载时同步摘干净,支持 PlugManX 热重载
 * (重载后统计归零,压缩上下文重新开始)。
 */
internal object Injector : Listener {

    private const val SHADOW = "netsqueeze_shadow"
    private const val WIRE = "netsqueeze_wire"

    /** 卸载时最多等多久摘完,拍脑袋定的 */
    private const val SHUTDOWN_WAIT_MS = 3000L

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
        val futures = Bukkit.getOnlinePlayers().map { player ->
            val channel = channelOf(player)
            channel.eventLoop().submit {
                val pipeline = channel.pipeline()
                if (pipeline.get(SHADOW) != null) pipeline.remove(SHADOW)
                if (pipeline.get(WIRE) != null) pipeline.remove(WIRE)
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
            if (!channel.isActive || pipeline.get(SHADOW) != null) return@execute
            // 阈值设成 -1 时没有 compress,影子就直接挂在 prepender 尾侧
            val anchor = if (pipeline.get(HandlerNames.COMPRESS) != null) HandlerNames.COMPRESS else HandlerNames.PREPENDER
            pipeline.addAfter(anchor, SHADOW, ShadowCompressor(tally))
            pipeline.addBefore(HandlerNames.PREPENDER, WIRE, WireCounter(tally))
            ShadowStats.samplePipeline = pipeline.names()
        }
    }

    private fun channelOf(player: Player): Channel = (player as CraftPlayer).handle.connection.connection.channel
}
