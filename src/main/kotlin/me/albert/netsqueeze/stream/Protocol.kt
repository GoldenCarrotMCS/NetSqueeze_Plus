package me.albert.netsqueeze.stream

import me.albert.netsqueeze.platform.ServerNetwork

/** v3 marked frames and unchanged v1 Deflate fallback use distinct channel names. */
internal object Protocol {
    const val VERSION = 3
    val HELLO: Any by lazy { ServerNetwork.packetAccess().id("hello_v$VERSION") }
    val HELLO_ZSTD: Any by lazy { ServerNetwork.packetAccess().id("hello_v${VERSION}_zstd") }
    val LEGACY_HELLO: Any by lazy { ServerNetwork.packetAccess().id("hello_v1") }
    private val ENABLE: Any by lazy { ServerNetwork.packetAccess().id("enable_v$VERSION") }
    private val ENABLE_ZSTD: Any by lazy { ServerNetwork.packetAccess().id("enable_v${VERSION}_zstd") }
    private val LEGACY_ENABLE: Any by lazy { ServerNetwork.packetAccess().id("enable") }
    fun enablePacket(useZstd: Boolean = false, marked: Boolean = true): Any =
        ServerNetwork.packetAccess().packet(
            if (!marked) LEGACY_ENABLE else if (useZstd) ENABLE_ZSTD else ENABLE, ByteArray(0))
    fun reconnectPacket(): Any = ServerNetwork.packetAccess().reconnectPacket()
}
