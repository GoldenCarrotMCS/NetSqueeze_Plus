package me.albert.netsqueeze.platform

import java.lang.reflect.Constructor

/** All unavoidable NMS signatures live here. Resolve once, never scan fields per packet. */
internal class PacketAccess(private val loader: ClassLoader) {
    private fun type(name: String): Class<*> = Class.forName(name, false, loader)
    private val identifier = type("net.minecraft.resources.Identifier")
    private val payload = type("net.minecraft.network.protocol.common.custom.CustomPacketPayload")
    private val discarded = type("net.minecraft.network.protocol.common.custom.DiscardedPayload")
    private val outbound = type("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket")
    private val inbound = type("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket")
    private val identify = identifier.getMethod("fromNamespaceAndPath", String::class.java, String::class.java)
    private val discardedCtor: Constructor<*> = discarded.getConstructor(identifier, ByteArray::class.java)
    private val outboundCtor = outbound.getConstructor(payload)
    private val readPayload = inbound.getMethod("payload")
    private val payloadType = payload.getMethod("type")
    private val payloadId = type("net.minecraft.network.protocol.common.custom.CustomPacketPayload\$Type").getMethod("id")
    private val component = type("net.minecraft.network.chat.Component")
    private val literal = component.getMethod("literal", String::class.java)
    private val disconnect = type("net.minecraft.network.protocol.common.ClientboundDisconnectPacket").getConstructor(component)
    private val readProtocol = type("net.minecraft.SharedConstants").getMethod("getProtocolVersion")

    fun protocolVersion(): Int = (readProtocol.invoke(null) as Number).toInt()
    fun id(path: String): Any = identify.invoke(null, "netsqueeze", path)
    fun packet(id: Any, data: ByteArray): Any = outboundCtor.newInstance(discardedCtor.newInstance(id, data))
    fun reconnectPacket(): Any = disconnect.newInstance(literal.invoke(null, "GoldenCarrot服务器网络组件更新，请重新连接"))
    fun payloadId(packet: Any): Any? = if (inbound.isInstance(packet)) {
        payloadId.invoke(payloadType.invoke(readPayload.invoke(packet)))
    } else null

    /** Check the Paper data-preserving codec contract, not merely constructor presence. */
    fun verify(): List<String> = buildList {
        check(discarded.getMethod("data").returnType == ByteArray::class.java) { "Paper payload bytes accessor changed" }
        check(inbound.getMethod("payload").returnType == payload)
        type("net.minecraft.network.PacketDecoder").getDeclaredField("protocolInfo")
        type("net.minecraft.network.PacketEncoder")
        add("custom-payload, disconnect, decoder, protocol-version signatures")
    }
}
