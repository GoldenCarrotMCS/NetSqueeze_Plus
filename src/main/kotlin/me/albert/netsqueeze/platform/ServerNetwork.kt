package me.albert.netsqueeze.platform

import io.netty.channel.Channel
import me.albert.netsqueeze.protocol.MinecraftVersions
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 缓存解析连接访问链，避免逐包扫描反射；初始化失败时尚未注册任何网络处理器。 */
internal object ServerNetwork {
    const val SUPPORTED_VERSION = "26+ 正式版本（需要网络 API 能力检查通过）"
    private data class ChannelAccess(
        val handle: Method, val listener: Field, val connection: Field, val channel: Field
    ) {
        fun channelOf(player: Player): Channel =
            channel.get(connection.get(listener.get(handle.invoke(player)))) as Channel
    }
    private lateinit var channelAccess: ChannelAccess
    private val packets by lazy { PacketAccess(ServerNetwork::class.java.classLoader) }

    fun supports(version: String) = MinecraftVersions.eligible(version)
    fun packetAccess(): PacketAccess = packets
    fun protocolVersion(): Int = packets.protocolVersion()

    fun initialize(version: String) {
        require(supports(version)) { "只接受 Minecraft 26 及之后正式版本，当前 $version" }
        packets.verify()
        channelAccess = resolveChannelAccess()
        check(protocolVersion() > 0) { "无法读取当前协议号" }
        Bukkit.getOnlinePlayers().forEach { channelAccess.channelOf(it) }
    }

    private fun resolveChannelAccess(): ChannelAccess {
        val loader = ServerNetwork::class.java.classLoader
        val player = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer", false, loader)
        val handle = player.getMethod("getHandle")
        val listener = handle.returnType.getField("connection")
        val connection = listener.type.getField("connection")
        val channel = connection.type.getField("channel")
        check(Channel::class.java.isAssignableFrom(channel.type)) { "服务端连接不再使用 Netty Channel" }
        return ChannelAccess(handle, listener, connection, channel)
    }

    fun verifyApi(): List<String> {
        val signatures = packets.verify()
        resolveChannelAccess()
        return signatures + "CraftPlayer -> listener -> Connection -> Channel"
    }

    /** 不访问或记录转发密钥；无法识别时使用隧道。 */
    fun proxyConfigured(): Boolean? = try {
        val type = Class.forName("io.papermc.paper.configuration.GlobalConfiguration")
        val config = type.getMethod("get").invoke(null)
        val proxies = type.getField("proxies").get(config)
        val velocity = proxies.javaClass.getField("velocity").get(proxies)
        velocity.javaClass.getField("enabled").getBoolean(velocity) ||
            Bukkit.spigot().config.getBoolean("settings.bungeecord", false)
    } catch (_: ReflectiveOperationException) { null
    } catch (_: RuntimeException) { null }

    fun channelOf(player: Player): Channel = channelAccess.channelOf(player)
}
