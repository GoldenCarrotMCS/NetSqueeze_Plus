package me.albert.netsqueeze.stream

import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import net.minecraft.resources.Identifier

/**
 * 和客户端 mod 的握手约定(两边改协议时一起改 [VERSION],对不上的客户端照走原版):
 * 1. 客户端进配置阶段时发 `netsqueeze:hello`,载荷 1 字节版本号;
 * 2. 服务端版本对得上就回 `netsqueeze:enable`(同样 1 字节版本号),这是这条连接最后一个原版格式的包,
 *    之后服务端发出的字节全是 [FrameDeflater] 的帧;客户端在 netty 线程上一解到它就切换解码。
 * 上行(客户端发服务端)不动,照走原版。
 */
internal object Protocol {

    const val VERSION = 1

    val HELLO: Identifier = Identifier.fromNamespaceAndPath("netsqueeze", "hello")

    val ENABLE: Identifier = Identifier.fromNamespaceAndPath("netsqueeze", "enable")

    fun enablePacket() = ClientboundCustomPayloadPacket(DiscardedPayload(ENABLE, byteArrayOf(VERSION.toByte())))

    /** 热重载时踢掉已切换的连接用:新格式的流没法无缝交接给新实例,只能让他们重连 */
    fun reconnectPacket() = ClientboundDisconnectPacket(Component.literal("服务器网络组件更新，请重新连接"))
}
