package me.albert.netsqueeze.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import java.util.concurrent.ConcurrentHashMap

/** 关闭时立即淘汰；卸载时还要移除 listener，避免原版连接持有旧插件类加载器。 */
internal class ChannelRegistry {
    private val listeners = ConcurrentHashMap<Channel, ChannelFutureListener>()

    @Synchronized
    fun track(channel: Channel) {
        val listener = ChannelFutureListener { forget(channel) }
        if (listeners.putIfAbsent(channel, listener) == null) channel.closeFuture().addListener(listener)
    }

    @Synchronized
    fun forget(channel: Channel) {
        val listener = listeners.remove(channel) ?: return
        channel.closeFuture().removeListener(listener)
    }

    @Synchronized
    fun snapshot(): Set<Channel> = listeners.keys.toSet()
}
