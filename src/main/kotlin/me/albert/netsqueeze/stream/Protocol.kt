package me.albert.netsqueeze.stream

import me.albert.netsqueeze.platform.ServerNetwork

/** 直连 v1 线格式不变；Minecraft 消息构造集中在已检查的适配器内。 */
internal object Protocol {
    const val VERSION = 1
    val HELLO: Any by lazy { ServerNetwork.packetAccess().id("hello_v$VERSION") }
    private val ENABLE: Any by lazy { ServerNetwork.packetAccess().id("enable") }
    fun enablePacket(): Any = ServerNetwork.packetAccess().packet(ENABLE, ByteArray(0))
    fun reconnectPacket(): Any = ServerNetwork.packetAccess().reconnectPacket()
}
