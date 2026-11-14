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
            logger.severe("NetSqueeze 要求 Minecraft ${ServerNetwork.SUPPORTED_VERSION}，当前服务器是 $serverVersion；插件将禁用。")
            server.pluginManager.disablePlugin(this)
            return
        }
        try {
            ServerNetwork.initialize(serverVersion)
        } catch (e: Exception) {
            logger.severe("Minecraft $serverVersion 网络 API 能力检查失败：${e.javaClass.simpleName}: ${e.message}；未注入网络处理器，请更新插件。")
            server.pluginManager.disablePlugin(this)
            return
        } catch (e: LinkageError) {
            logger.severe("Minecraft $serverVersion 服务端 API 链接失败：${e.message}；未注入网络处理器。")
            server.pluginManager.disablePlugin(this)
            return
        }
        if (serverVersion !in me.albert.netsqueeze.protocol.MinecraftVersions.CI_VERSIONS) {
            logger.warning("Minecraft $serverVersion 未进入已发布版本 CI 矩阵；能力检查通过不等于完整运行时兼容保证。")
        }
        logger.info("Minecraft $serverVersion，协议号 ${ServerNetwork.protocolVersion()}，网络 API 检查通过")
        saveDefaultConfig()
        if (!config.contains("chunk-cache-namespace") || config.getString("chunk-cache-namespace") == "00000000-0000-0000-0000-000000000000") {
            config.set("chunk-cache-namespace", java.util.UUID.randomUUID().toString())
            saveConfig()
        }
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
        if (settings.zstdEnabled) {
            if (me.albert.netsqueeze.stream.ZstdFrame.available()) {
                logger.info("大包 ZSTD 就绪：阈值 ${settings.zstdMinPacketBytes} B，级别 ${settings.zstdLevel}；仅协商成功后启用。")
                logger.info("ZSTD 字典：${settings.zstdDictionaryEnabled && me.albert.netsqueeze.stream.ZstdFrame.dictionaryAvailable()}；每连接内存最多 64 KiB，仅新能力协商后使用。")
            } else {
                logger.warning("ZSTD 原生库不可用或被系统属性禁用，保留 Deflate；不会声明 ZSTD 能力。")
            }
        }
        val useTunnel = try {
            settings.transportMode.useTunnel(ServerNetwork.proxyConfigured()) || settings.chunkCacheEnabled
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
        me.albert.netsqueeze.tunnel.TunnelCompressor.stopWorkers()
    }
}
