package me.albert.netsqueeze.platform

import io.netty.channel.Channel
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player

/** 所有 CraftBukkit 的连接访问集中于此；新增版本需先独立验证，不能只放宽版本字符串。 */
internal object ServerNetwork {
    const val SUPPORTED_VERSION = "26.1.2"

    fun supports(version: String) = version == SUPPORTED_VERSION

    /** 仅启动时探测。不读取或记录 forwarding secret；无法确认时选安全的代理隧道。 */
    fun proxyConfigured(): Boolean? = try {
        val type = Class.forName("io.papermc.paper.configuration.GlobalConfiguration")
        val config = type.getMethod("get").invoke(null)
        val proxies = type.getField("proxies").get(config)
        val velocity = proxies.javaClass.getField("velocity").get(proxies)
        velocity.javaClass.getField("enabled").getBoolean(velocity) ||
            Bukkit.spigot().config.getBoolean("settings.bungeecord", false)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    fun channelOf(player: Player): Channel =
        (player as CraftPlayer).handle.connection.connection.channel
}
