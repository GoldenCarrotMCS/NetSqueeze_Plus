package me.albert.netsqueeze

import me.albert.netsqueeze.shadow.Injector
import me.albert.netsqueeze.shadow.StatsCommand
import org.bukkit.plugin.java.JavaPlugin

internal lateinit var instance: NetSqueeze
    private set

internal val logger get() = instance.logger

class NetSqueeze : JavaPlugin() {

    override fun onEnable() {
        instance = this
        Injector.init()
        StatsCommand.init()
        logger.info("$name 已加载 ✅")
    }

    override fun onDisable() {
        Injector.shutdown()
    }
}
