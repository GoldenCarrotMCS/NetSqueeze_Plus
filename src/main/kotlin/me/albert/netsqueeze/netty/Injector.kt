package me.albert.netsqueeze.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelPipeline
import io.papermc.paper.network.ChannelInitializeListenerHolder
import me.albert.corelib.utils.deleteMetadata
import me.albert.corelib.utils.registerEvents
import me.albert.corelib.utils.setMetadata
import me.albert.netsqueeze.instance
import me.albert.netsqueeze.stats.TrafficStats
import me.albert.netsqueeze.stats.WireCounter
import me.albert.netsqueeze.stream.HelloHandler
import me.albert.netsqueeze.stream.Protocol
import me.albert.netsqueeze.stream.StreamCompressor
import net.kyori.adventure.key.Key
import net.minecraft.network.HandlerNames
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * 管线注入。每条新连接一建好就在 packet_handler 前挂握手处理器([HelloHandler]);装了 mod 的在配置阶段握手成功后
 * 挂真压缩([StreamCompressor]),没装的进服时只挂原版出网计数。出站方向(从尾到头)的顺序是:
 * encoder(Via/PacketEvents 挂在它附近)→ **真压缩** → compress → prepender → **原版计数** → encrypt。
 *
 * 处理器随连接关闭由 netty 自动拆掉;插件卸载时同步摘干净,支持 PlugManX 热重载:已切到真压缩的连接,
 * 新格式的流没法交接给新实例,发一条断线消息让他们重连;其余连接无感(统计归零,压缩上下文重新开始)。
 */
internal object Injector : Listener {

    private const val HELLO = "netsqueeze_hello"
    private const val STREAM = "netsqueeze_stream"
    private const val WIRE = "netsqueeze_wire"

    /** 装了 mod 的在线玩家身上的 metadata(值 true),别的插件 `player.hasMetadata("netsqueeze_mod")` 判断,发奖励等 */
    private const val MOD_META = "netsqueeze_mod"

    private val LISTENER_KEY = Key.key("netsqueeze", "hello")

    /** 卸载时最多等多久摘完,拍脑袋定的 */
    private const val SHUTDOWN_WAIT_MS = 3000L

    /** 挂过握手处理器的连接(含还没进服的),卸载时逐个摘;关掉的在新连接进来时顺手清 */
    private val channels: MutableSet<Channel> = ConcurrentHashMap.newKeySet()

    /**
     * 卸载开始后不再挂新的。Folia 上进服事件在各区域线程并发触发,onDisable 跑完到 Bukkit 注销监听器之间
     * 还可能有人进服,它排的注入任务若排在摘除任务后面,就会把旧类加载器的处理器挂回去
     */
    @Volatile
    private var stopped = false

    fun init() {
        instance.registerEvents(this)
        ChannelInitializeListenerHolder.addListener(LISTENER_KEY, ::onChannelInit)
        Bukkit.getOnlinePlayers().forEach(::inject)
    }

    /**
     * 必须等全部摘完再返回:热重载紧接着关掉本插件的类加载器,摘掉之前旧处理器还在 netty 线程上处理包,
     * 碰到没加载过的类就 NoClassDefFoundError,netty 把它当连接异常处理会踢人。
     * 摘除时 handlerRemoved 顺带关掉 deflate 流,不漏原生内存。
     */
    fun shutdown() {
        stopped = true
        ChannelInitializeListenerHolder.removeListener(LISTENER_KEY)
        // 标记挂在 CoreLib 名下,本插件卸载不会自动清;装 mod 的下面会被踢,先摘掉
        Bukkit.getOnlinePlayers().forEach { it.deleteMetadata(MOD_META) }
        val targets = HashSet(channels)
        Bukkit.getOnlinePlayers().forEach { targets += channelOf(it) }
        val futures = targets.filter { it.isOpen }.map { channel ->
            channel.eventLoop().submit {
                val pipeline = channel.pipeline()
                // 断线消息还得走这条流发出去,所以先发、关连接,再摘处理器
                if (pipeline.get(STREAM) != null && channel.isActive) {
                    channel.writeAndFlush(Protocol.reconnectPacket())
                    channel.close()
                }
                removeIfPresent(pipeline, HELLO, STREAM, WIRE)
            }
        }
        // 各连接分散在几条 netty 线程上并行摘,正常几毫秒;总等待封顶,netty 线程卡死也不至于把卸载拖住
        val deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS
        futures.forEach { it.awaitUninterruptibly((deadline - System.currentTimeMillis()).coerceAtLeast(0)) }
    }

    /**
     * 握手成功,在这条连接的 netty 线程上调。先用原版格式发 enable,紧接着挂真压缩:同一个 netty 任务里做完,
     * 中间插不进别的出站字节,enable 之后的全是新格式。只在配置阶段切(进服后才来的不理,原版计数已经挂上了)
     */
    fun enableStream(channel: Channel) {
        val pipeline = channel.pipeline()
        if (stopped || !channel.isActive || pipeline.get(HandlerNames.PREPENDER) == null) return
        if (pipeline.get(STREAM) != null || pipeline.get(WIRE) != null) return
        channel.writeAndFlush(Protocol.enablePacket())
        pipeline.addAfter(anchorOf(pipeline), STREAM, StreamCompressor())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) = inject(e.player)

    /**
     * 握手在配置阶段就完了,进服时管线里有没有真压缩就是装没装 mod。LOWEST 打标,别的插件在自己的进服监听里就能读到。
     * 没装的也删一遍:metadata 按名字存、不随下线清,防同名残留上次的值
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoinMark(e: PlayerJoinEvent) {
        val player = e.player
        if (channelOf(player).pipeline().get(STREAM) == null) {
            player.deleteMetadata(MOD_META)
            return
        }
        player.setMetadata(MOD_META, true)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) = e.player.deleteMetadata(MOD_META)

    /** netty 线程上、连接刚建好时调,这时 packet_handler 已经在管线里 */
    private fun onChannelInit(channel: Channel) {
        channels.removeIf { !it.isOpen }
        channels += channel
        // 先登记再查 stopped,和 shutdown 的"先置位再取快照"配对:两边总有一边看得到对方
        if (stopped) return
        channel.pipeline().addBefore(HandlerNames.PACKET_HANDLER, HELLO, HelloHandler())
    }

    private fun inject(player: Player) {
        val channel = channelOf(player)
        val tally = TrafficStats.tally(player)
        // 管线只能在自己的 netty 线程上改,和正在发的包排队,不会插到半个包中间
        channel.eventLoop().execute {
            val pipeline = channel.pipeline()
            // 在 netty 线程上查 stopped:置位后才跑到这里的一律不挂;置位前已经挂上的,摘除任务排在它后面会摘掉
            if (stopped || !channel.isActive) return@execute
            // 握手只在配置阶段,到进服还没握的就是没装 mod
            removeIfPresent(pipeline, HELLO)
            val stream = pipeline.get(STREAM) as? StreamCompressor
            if (stream != null) {
                stream.tally = tally
                tally.streaming = true
                TrafficStats.sampleStreamPipeline = pipeline.names()
                return@execute
            }
            if (pipeline.get(WIRE) != null) return@execute
            pipeline.addBefore(HandlerNames.PREPENDER, WIRE, WireCounter(tally))
            TrafficStats.samplePipeline = pipeline.names()
        }
    }

    /** 阈值设成 -1 时没有 compress,真压缩就直接挂在 prepender 尾侧 */
    private fun anchorOf(pipeline: ChannelPipeline) =
        if (pipeline.get(HandlerNames.COMPRESS) != null) HandlerNames.COMPRESS else HandlerNames.PREPENDER

    private fun removeIfPresent(pipeline: ChannelPipeline, vararg names: String) {
        for (name in names) {
            if (pipeline.get(name) != null) pipeline.remove(name)
        }
    }

    private fun channelOf(player: Player): Channel = (player as CraftPlayer).handle.connection.connection.channel
}
