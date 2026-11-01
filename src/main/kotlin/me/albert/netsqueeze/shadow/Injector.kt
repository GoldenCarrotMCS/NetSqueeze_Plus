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
 * 处理器随连接关闭由 netty 自动拆掉,退服不用管;插件卸载时主动摘。
 */
internal object Injector : Listener {

    private const val SHADOW = "netsqueeze_shadow"
    private const val WIRE = "netsqueeze_wire"

    fun init() {
        instance.registerEvents(this)
        Bukkit.getOnlinePlayers().forEach(::inject)
    }

    fun shutdown() {
        Bukkit.getOnlinePlayers().forEach { player ->
            val channel = channelOf(player)
            channel.eventLoop().execute {
                val pipeline = channel.pipeline()
                if (pipeline.get(SHADOW) != null) pipeline.remove(SHADOW)
                if (pipeline.get(WIRE) != null) pipeline.remove(WIRE)
            }
        }
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
