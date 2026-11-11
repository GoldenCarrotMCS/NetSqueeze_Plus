package me.albert.netsqueeze

import me.albert.netsqueeze.netty.Injector
import me.albert.netsqueeze.config.Settings
import me.albert.netsqueeze.platform.ServerNetwork
import me.albert.netsqueeze.stats.StreamStats
import me.albert.netsqueeze.stats.StatsCommand
import org.bukkit.plugin.java.JavaPlugin

internal lateinit var instance: NetSqueeze
    private set

internal val logger get() = instance.logger

class NetSqueeze : JavaPlugin() {

    private var initialized = false

    override fun onEnable() {
        instance = this
        val serverVersion = server.minecraftVersion
        if (!ServerNetwork.supports(serverVersion)) {
            logger.severe("NetSqueeze 只支持已验证的 Minecraft ${ServerNetwork.SUPPORTED_VERSION}，当前服务器是 $serverVersion；插件将禁用。")
            server.pluginManager.disablePlugin(this)
            return
        }
        saveDefaultConfig()
        val settings = try {
            Settings.load(config)
        } catch (e: IllegalArgumentException) {
            logger.severe("配置错误：${e.message}；插件将禁用。")
            server.pluginManager.disablePlugin(this)
            return
        }
        if (!settings.enabled) {
            logger.info("NetSqueeze 已由 enabled: false 关闭")
            server.pluginManager.disablePlugin(this)
            return
        }
        val useTunnel = try {
            settings.transportMode.useTunnel(ServerNetwork.proxyConfigured())
        } catch (e: IllegalArgumentException) {
            logger.severe("传输模式错误：${e.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        logger.info("GoldenCarrot服务器网络模式：${if (useTunnel) "Velocity 兼容隧道" else "直连流式压缩"}")
        StreamStats.logFirstFailure = settings.logFirstFailure
        // 注册进行到一半也必须能在 onDisable 清理。
        initialized = true
        Injector.init(settings, useTunnel)
        StatsCommand.init()
        logger.info("$name 已加载 ✅")
    }

    override fun onDisable() {
        if (!initialized) return
        Injector.shutdown()
    }
}
