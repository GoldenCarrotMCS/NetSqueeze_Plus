package me.albert.netsqueeze.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelPipeline
import io.papermc.paper.network.ChannelInitializeListenerHolder
import me.albert.netsqueeze.config.Settings
import me.albert.netsqueeze.instance
import me.albert.netsqueeze.logger
import me.albert.netsqueeze.platform.ServerNetwork.channelOf
import me.albert.netsqueeze.stats.PlayerTally
import me.albert.netsqueeze.tunnel.TunnelCompressor
import me.albert.netsqueeze.tunnel.TunnelGate
import me.albert.netsqueeze.tunnel.TunnelHandshake
import me.albert.netsqueeze.stats.TrafficStats
import me.albert.netsqueeze.stats.WireCounter
import me.albert.netsqueeze.stream.HelloHandler
import me.albert.netsqueeze.stream.Protocol
import me.albert.netsqueeze.stream.StreamCompressor
import me.albert.netsqueeze.stream.ZstdFrame
import net.kyori.adventure.key.Key
import me.albert.netsqueeze.platform.NetworkNames as HandlerNames
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.metadata.FixedMetadataValue

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
    private const val TUNNEL = "netsqueeze_tunnel"
    private const val GATE = "netsqueeze_tunnel_gate"
    private const val ACK = "netsqueeze_tunnel_ack"
    private const val WIRE = "netsqueeze_wire"

    /** 装了 mod 的在线玩家身上的 metadata(值 true),别的插件 `player.hasMetadata("netsqueeze_mod")` 判断,发奖励等 */
    private const val MOD_META = "netsqueeze_mod"

    private val LISTENER_KEY = Key.key("netsqueeze", "hello")

    /** 卸载时最多等多久摘完,拍脑袋定的 */
    private const val SHUTDOWN_WAIT_MS = 3000L

    private val channels = ChannelRegistry()
    private lateinit var settings: Settings
    private var useTunnel = false

    /**
     * 卸载开始后不再挂新的。Folia 上进服事件在各区域线程并发触发,onDisable 跑完到 Bukkit 注销监听器之间
     * 还可能有人进服,它排的注入任务若排在摘除任务后面,就会把旧类加载器的处理器挂回去
     */
    @Volatile
    private var stopped = false

    fun init(settings: Settings, useTunnel: Boolean) {
        this.useTunnel = useTunnel
        this.settings = settings
        stopped = false
        Bukkit.getPluginManager().registerEvents(this, instance)
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
        // 装 mod 的下面会被踢,标记先摘掉
        Bukkit.getOnlinePlayers().forEach { it.removeMetadata(MOD_META, instance) }
        val targets = channels.snapshot().toMutableSet()
        Bukkit.getOnlinePlayers().forEach { targets += channelOf(it) }
        targets.forEach(channels::forget)
        val futures = targets.filter { it.isOpen }.map { channel ->
            channel.eventLoop().submit {
                val pipeline = channel.pipeline()
                // 断线消息还得走这条流发出去,所以先发、关连接,再摘处理器
                if (pipeline.get(STREAM) != null && channel.isActive) {
                    channel.writeAndFlush(Protocol.reconnectPacket())
                    channel.close()
                }
                (pipeline.get(TUNNEL) as? TunnelCompressor)?.pause()
                channel.flush()
                removeIfPresent(pipeline, ACK, GATE, TUNNEL, HELLO, STREAM, WIRE)
            }
        }
        // 各连接分散在几条 netty 线程上并行摘,正常几毫秒;总等待封顶,netty 线程卡死也不至于把卸载拖住
        val deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS
        futures.forEach {
            if (!it.awaitUninterruptibly((deadline - System.currentTimeMillis()).coerceAtLeast(0)) || !it.isSuccess) {
                logger.severe("网络处理器卸载未完成，不能安全热重载；请完整重启服务器。原因：${it.cause()}")
            }
        }
    }

    /**
     * 握手成功,在这条连接的 netty 线程上调。先用原版格式发 enable,紧接着挂真压缩:同一个 netty 任务里做完,
     * 中间插不进别的出站字节,enable 之后的全是新格式。只在配置阶段切(进服后才来的不理,原版计数已经挂上了)
     */
    fun enableStream(channel: Channel, useZstd: Boolean, markedFrames: Boolean, useDictionary: Boolean = false) {
        val pipeline = channel.pipeline()
        if (useTunnel || stopped || !channel.isActive || pipeline.get(HandlerNames.PREPENDER) == null) return
        if (pipeline.get(STREAM) != null || pipeline.get(WIRE) != null) return
        val negotiatedZstd = markedFrames && useZstd && settings.zstdEnabled && ZstdFrame.available()
        val dictionary = negotiatedZstd && useDictionary && settings.zstdDictionaryEnabled && ZstdFrame.dictionaryAvailable()
        channel.writeAndFlush(Protocol.enablePacket(negotiatedZstd, markedFrames, dictionary))
        pipeline.addAfter(anchorOf(pipeline), STREAM, StreamCompressor(
            settings.compressionLevel, settings.maxFrameRawBytes, negotiatedZstd,
            settings.zstdMinPacketBytes, settings.zstdLevel, markedFrames, dictionary
        ))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) = inject(e.player)

    /**
     * 直连 v1 在配置阶段完成，LOWEST 可打标；代理隧道在 PLAY 收到 ACK 后另行打标。
     * 没装的也删一遍:metadata 按名字存、不随下线清,防同名残留上次的值
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoinMark(e: PlayerJoinEvent) {
        val player = e.player
        if (channelOf(player).pipeline().get(STREAM) == null) {
            player.removeMetadata(MOD_META, instance)
            return
        }
        player.setMetadata(MOD_META, FixedMetadataValue(instance, true))
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        e.player.removeMetadata(MOD_META, instance)
        TrafficStats.endSession(e.player.uniqueId, channelOf(e.player))
    }

    /** netty 线程上、连接刚建好时调,这时 packet_handler 已经在管线里 */
    private fun onChannelInit(channel: Channel) {
        channels.track(channel)
        // 先登记再查 stopped,和 shutdown 的"先置位再取快照"配对:两边总有一边看得到对方
        if (stopped) {
            channels.forget(channel)
            return
        }
        if (channel.pipeline().get(HandlerNames.PACKET_HANDLER) == null ||
            channel.pipeline().get(HandlerNames.PREPENDER) == null) {
            logger.warning("连接管线缺少已验证锚点，保留原版传输：${channel.pipeline().names()}")
            return
        }
        if (!useTunnel) channel.pipeline().addBefore(HandlerNames.PACKET_HANDLER, HELLO, HelloHandler())
    }

    private fun inject(player: Player) {
        if (stopped) return
        val channel = channelOf(player)
        val tally = TrafficStats.tally(player, channel)
        // 管线只能在自己的 netty 线程上改,和正在发的包排队,不会插到半个包中间
        channel.eventLoop().execute {
            val pipeline = channel.pipeline()
            // 在 netty 线程上查 stopped:置位后才跑到这里的一律不挂;置位前已经挂上的,摘除任务排在它后面会摘掉
            if (stopped || !channel.isActive) return@execute
            if (pipeline.get(HandlerNames.PREPENDER) == null || pipeline.get(HandlerNames.ENCODER) == null) {
                logger.warning("连接缺少编码管线锚点，保持原版传输：${pipeline.names()}")
                return@execute
            }
            // 没有 v1 则保留标准链；代理客户端仍可能在 PLAY 完成隧道协商。
            removeIfPresent(pipeline, HELLO)
            val stream = pipeline.get(STREAM) as? StreamCompressor
            tally.startSession(stream != null)
            if (stream != null) {
                stream.tally = tally
                TrafficStats.sampleStreamPipeline = pipeline.names()
                return@execute
            }
            if (useTunnel && pipeline.get(TUNNEL) == null) installTunnel(channel, player, tally)
            if (pipeline.get(WIRE) != null) return@execute
            pipeline.addBefore(HandlerNames.PREPENDER, WIRE, WireCounter(tally))
            TrafficStats.samplePipeline = pipeline.names()
        }
    }

    private fun installTunnel(channel: Channel, player: Player, tally: PlayerTally) {
        val pipeline = channel.pipeline()
        if (pipeline.get(HandlerNames.ENCODER) == null) return
        lateinit var bridge: TunnelCompressor
        bridge = TunnelCompressor(settings, tally) { active ->
            TrafficStats.sampleTunnelPipeline = pipeline.names()
            // ACK 在 Netty，metadata 必须回玩家自己的区域线程；转服/退服后不能标到新会话。
            if (!stopped) player.scheduler.run(instance, { _ ->
                if (!stopped && channel.isActive && pipeline.get(TUNNEL) === bridge && bridge.enabled == active) {
                    if (active) player.setMetadata(MOD_META, FixedMetadataValue(instance, true))
                    else player.removeMetadata(MOD_META, instance)
                }
            }, null)
        }
        pipeline.addAfter(anchorOf(pipeline), TUNNEL, bridge)
        pipeline.addAfter(HandlerNames.ENCODER, GATE, TunnelGate(bridge))
        pipeline.addBefore(HandlerNames.PACKET_HANDLER, ACK, TunnelHandshake(bridge))
        bridge.offer()
    }

    /** 阈值设成 -1 时没有 compress,真压缩就直接挂在 prepender 尾侧 */
    private fun anchorOf(pipeline: ChannelPipeline) =
        if (pipeline.get(HandlerNames.COMPRESS) != null) HandlerNames.COMPRESS else HandlerNames.PREPENDER

    private fun removeIfPresent(pipeline: ChannelPipeline, vararg names: String) {
        for (name in names) {
            if (pipeline.get(name) != null) pipeline.remove(name)
        }
    }
}
